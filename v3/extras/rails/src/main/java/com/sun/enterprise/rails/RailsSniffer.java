/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.rails;

import org.glassfish.internal.deployment.GenericSniffer;
import com.sun.enterprise.module.impl.CookedModuleDefinition;
import com.sun.enterprise.module.*;
import org.glassfish.api.container.Sniffer;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.Singleton;
import org.jvnet.hk2.component.Habitat;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.jar.JarFile;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;

/**
 * JRuby sniffer
 */
@Service(name = "rails")
@Scoped(Singleton.class)
public class RailsSniffer extends GenericSniffer implements Sniffer {

    @Inject
    Habitat habitat;

    @Inject
    ModulesRegistry registry;

    public RailsSniffer() {
        super("jruby", "app/controllers/application.rb", null);
    }


    final String[] containers = {"com.sun.enterprise.rails.RailsContainer"};

    /**
     * Sets up the container libraries so that any imported bundle from the
     * connector jar file will now be known to the module subsystem
     * <p/>
     * This method returns a {@link ModuleDefinition} for the module containing
     * the core implementation of the container. That means that this module
     * will be locked as long as there is at least one module loaded in the
     * associated container.
     *
     * @param containerHome is where the container implementation resides
     * @param logger        the logger to use
     * @return the module definition of the core container implementation.
     * @throws java.io.IOException exception if something goes sour
     */
    public Module[] setup(String containerHome, Logger logger) throws IOException {
        super.setup(containerHome, logger);



        File rootLocation = new File(containerHome);
        if (!rootLocation.exists()) {
            throw new RuntimeException("JRuby installation not found at " + rootLocation.getPath());
        }

        Module m = null;
        rootLocation = new File(rootLocation, "lib");
        String moduleSystem = System.getProperty("GlassFish_Platform");
        try {
            if (moduleSystem==null || moduleSystem.equals("HK2")) {
                m = setUpHk2(rootLocation);
            } else {
                setUpOSGi(rootLocation);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Cannot setup jruby classpath", e);
            throw e;
        }

        Module grizzlyRails = registry.makeModuleFor("org.glassfish.external:grizzly-jruby-module", null);
        if (grizzlyRails==null) {
            logger.log(Level.SEVERE, "Cannot find the grizzly-jruby-module");
            throw new IOException("cannot find the grizzly-jruby-module");
        }
        if (m!=null) {
             grizzlyRails.addImport(m);
        }
        
        Module[] modules = { grizzlyRails };
        return modules;
    }

    private Module setUpHk2(File libDirectory) throws IOException {
        Attributes jrubyAttr = new Attributes();
        StringBuffer classpath = new StringBuffer();
        for (File lib : libDirectory.listFiles()) {
            if (lib.isFile()) {
                if (lib.getName().equals("jruby.jar") || !lib.getName().endsWith(".jar")) {
                    continue;
                }

                classpath.append(lib.getName());
                classpath.append(" ");
            }
        }
        jrubyAttr.putValue(Attributes.Name.CLASS_PATH.toString(), classpath.toString());
        jrubyAttr.putValue(ManifestConstants.BUNDLE_NAME, "org.jruby:jruby-complete");

        ModuleDefinition jruby = new CookedModuleDefinition(
                new File(libDirectory, "jruby.jar"), jrubyAttr);

        return registry.add(jruby);

    }

    private Module setUpOSGi(File libDirectory) throws IOException {

        // we need to create a META-INF/MANIFEST file if it is not already there.
        File manifestFile = new File(libDirectory, JarFile.MANIFEST_NAME);
        manifestFile = manifestFile.getCanonicalFile();
        if (!manifestFile.exists()) {
            Manifest m = new Manifest();
            m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            m.getMainAttributes().putValue("Bundle-SymbolicName", "org.jruby.jruby");

            // calculate classpath
            StringBuffer classpath = new StringBuffer();
            for (File lib : libDirectory.listFiles()) {
                if (lib.isFile()) {
                    classpath.append(lib.getName());
                    classpath.append(",");
                }
            }

            m.getMainAttributes().putValue("Bundle-ClassPath", classpath.toString());
            m.getMainAttributes().putValue("Export-Package", "org.jruby,org.jruby.exceptions,org.jruby.runtime.builtin,org.jruby.javasupport,org.jruby.runtime.load,org.jruby.internal.runtime,org.jruby.runtime.callback,org.jruby.javasupport.util,org.jruby.ast.executable,org.jruby.runtime,org.jruby.libraries");
            try {
                if (!manifestFile.getParentFile().exists() && !manifestFile.getParentFile().mkdirs()) {
                    throw new IOException("Cannot create manifest file in jruby installation, do you have write access ?");                    
                }
                if (!manifestFile.createNewFile()) {
                    throw new IOException("Cannot create manifest file in jruby installation, do you have write access ?");
                }
                FileOutputStream out = new FileOutputStream(manifestFile);
                m.write(System.out);
                m.write(out);
                out.flush();
                out.close();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        Attributes jrubyAttr = new Attributes();
        jrubyAttr.putValue(ManifestConstants.BUNDLE_NAME, "org.jruby:jruby-complete");
        
        ModuleDefinition jruby = new CookedModuleDefinition(
                libDirectory, jrubyAttr);

        return registry.add(jruby);
        
    }


    public String[] getContainersNames() {
        return containers;
    }

    
    /**
     * @return whether this sniffer should be visible to user
     *
     */
    public boolean isUserVisible() {
        return true;
    }
    
}
