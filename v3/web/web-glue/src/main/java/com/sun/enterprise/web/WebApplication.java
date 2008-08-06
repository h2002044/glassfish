/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.enterprise.web;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.Container;
import org.apache.catalina.connector.CoyoteAdapter;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import com.sun.enterprise.deployment.EnvironmentProperty;
import com.sun.enterprise.deployment.web.EnvironmentEntry;
import com.sun.enterprise.deployment.web.ContextParameter;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.Result;
import com.sun.logging.LogDomains;
import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.ApplicationConfig;
import org.glassfish.api.container.EndpointRegistrationException;
import org.glassfish.api.container.RequestDispatcher;
import org.glassfish.api.deployment.ApplicationContainer;
import org.glassfish.web.loader.WebappClassLoader;
import org.glassfish.web.plugin.common.WebAppConfig;
import org.glassfish.web.plugin.common.EnvEntry;
import org.glassfish.web.plugin.common.ContextParam;

import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebApplication implements ApplicationContainer<WebBundleDescriptor> {

    private static final String ADMIN_VS = "__asadmin";
    final Logger logger = LogDomains.getLogger(LogDomains.WEB_LOGGER);

    final WebContainer container;
    final WebModuleConfig wmInfo;
    final RequestDispatcher dispatcher;

    public WebApplication(WebContainer container, WebModuleConfig config, RequestDispatcher dispatcher) {
        this.container = container;
        this.wmInfo = config;
        this.dispatcher = dispatcher;
    }


    public boolean start(ClassLoader cl) {
        wmInfo.setAppClassLoader(cl);
        applyApplicationConfig();
        String vsIDs = wmInfo.getVirtualServers();
        List<String> vsList = StringUtils.parseStringList(vsIDs, " ,");
        return start(vsList);
    }

    boolean start(List<String> vsList) {

        final String contextRoot = wmInfo.getDescriptor().getContextRoot();
        boolean loadToAll = (vsList == null) || (vsList.size() == 0);

        // TODO : dochez : add action report here...
        List<Result<WebModule>> results = container.loadWebModule(wmInfo, "null");
        if (results==null) {
            logger.log(Level.SEVERE, "Unknown error, loadWebModule returned null, file a bug");
            return false;
        }

        logger.info("Loading application " + wmInfo.getDescriptor().getName()
                + " at " + wmInfo.getDescriptor().getContextRoot());
        for (Result<com.sun.enterprise.web.WebModule> result : results) {
            // result will be null if the web module was merely resumed
            if (result != null) { 
                if (result.isSuccess()) {
                    VirtualServer vs = (VirtualServer) result.result().getParent();
                    final Collection<String> c = new HashSet<String>();
                    c.add(vs.getID());
                    if (loadToAll || vsList.contains(vs.getName())
                            || isAliasMatched(vsList,vs)) {
                        for (int port : vs.getPorts()) {
                            if ((container.getJkConnector()!=null) && 
                                    (port==container.getJkConnector().getPort())) {
                                // Do not registerEndpoint for jk connector port
                                continue;
                            }
                            CoyoteAdapter adapter =
                                container.adapterMap.get(Integer.valueOf(port));
                            //@TODO change EndportRegistrationException processing if required
                            try {
                                dispatcher.registerEndpoint(contextRoot,
                                    adapter.getPort(), c, adapter, this);
                            } catch(EndpointRegistrationException e) {
                                logger.log(Level.WARNING,
                                           "Error while deploying", e);
                            }
                        }
                    }
                } else {
                    logger.log(Level.SEVERE, "Error while deploying",
                               result.exception());
                    return false;
                }
            }
        }

        return true;
    }

    public boolean stop() {
        return stop(null);
    }

    boolean stop(List<String> targets) {

        boolean isLeftOver = false;
        boolean unloadFromAll = (targets == null) || (targets.size() == 0);
        final String ctxtRoot = getDescriptor().getContextRoot();

        Container[] hosts = container.engine.findChildren();
        for (int i = 0; i < hosts.length; i++) {
            StandardHost vs = (StandardHost) hosts[i];

            if (unloadFromAll && ADMIN_VS.equals(vs.getName())) {
                // Do not unload from __asadmin
                continue;
            }

            StandardContext ctxt = (StandardContext) vs.findChild(ctxtRoot);
            if (ctxt != null) {
                if (unloadFromAll
                        || targets.contains(vs.getName())
                        || isAliasMatched(targets, vs)) {
                    vs.removeChild(ctxt);
                    try {
                        ctxt.destroy();
                    } catch (Exception ex) {
                        logger.log(Level.WARNING,
                                "Unable to destroy web module "
                                        + ctxt, ex);
                    }
                    logger.info("Undeployed web module " + ctxt
                            + " from virtual server "
                            + vs.getName());
                    // ToDo : dochez : not good, we unregister from everywhere...
                    //@TODO change EndportRegistrationException processing if required
                    try {
                        dispatcher.unregisterEndpoint(ctxtRoot, this);
                    } catch (EndpointRegistrationException e) {
                        logger.log(Level.WARNING, "Error while undeploying", e);
                    }
                } else {
                    isLeftOver = true;
                }
            }
        }

        if ((unloadFromAll || !isLeftOver)
                && (getClassLoader() instanceof WebappClassLoader)) {
            try {
                ((WebappClassLoader) getClassLoader()).stop();
            } catch (Exception e) {
                logger.log(Level.WARNING,
                           "Unable to stop classloader for " + this, e);
            }
        }

        return true;
    }

    /**
     * Returns the class loader associated with this application
     *
     * @return ClassLoader for this app
     */
    public ClassLoader getClassLoader() {
        return wmInfo.getAppClassLoader();
    }

    WebContainer getContainer() {
        return container;
    }

    /**
     * Returns the deployment descriptor associated with this application
     *
     * @return deployment descriptor if they exist or null if not
     */
    public WebBundleDescriptor getDescriptor() {
        return wmInfo.getDescriptor();
    }

    /**
     * Suspends this application on all virtual servers.
     */
    public boolean suspend() {
        return suspend(null);
    }

    /**
     * Suspends this application on the specified virtual servers.
     *
     * @param vsList the virtual servers on which to suspend this application
     */
    public boolean suspend(List<String> vsList) {
        return container.suspendWebModule(
            wmInfo.getDescriptor().getContextRoot(), "null", vsList);
    }

    /**
     * Resumes this application on all virtual servers.
     */
    public boolean resume() {
        return resume(null);
    }

    /**
     * Resumes this application on the specified virtual servers.
     *
     * @param vsList the virtual servers on which to suspend this application
     */
    public boolean resume(List<String> vsList) {
        // WebContainer.loadWebModule(), which is called by start(), 
        // already checks if the web module has been suspended, and if so,
        // just resumes it and returns
        return start(vsList);
    }

    /*
     * @return true if the list of target virtual server names matches an
     * alias name of the given virtual server, and false otherwise
     */
    private boolean isAliasMatched(List targets, StandardHost vs){

        String[] aliasNames = vs.getAliases();
        for (int i=0; i<aliasNames.length; i++) {
            if (targets.contains(aliasNames[i]) ){
                return true;
            }
        }

        return false;
    }

    /**
     * Deploy on aliases as well as host.
     */
    private boolean verifyAlias(List vsList,VirtualServer vs){
        for(int i=0; i < vs.getAliases().length; i++){
            if (vsList.contains(vs.getAliases()[i]) ){
                return true;
            }
        }
        return false;
    }    


    private void applyApplicationConfig() {
        ApplicationConfig appConfig = 
            ConfigBeansUtilities.getApplicationConfigByType(
                container.instanceName, wmInfo.getName(), "web");
        if (appConfig != null) {
            // parse the appConfigData and set in the descriptor
            WebBundleDescriptor descriptor = wmInfo.getDescriptor();

            WebAppConfig c = appConfig.getConfigData(
                container._serverContext.getDefaultHabitat()); 

            for (EnvEntry env : c.getEnvEntry()) {
                EnvironmentEntry newEnvEntry = new EnvironmentProperty(
                    env.getEnvEntryName(), env.getEnvEntryValue(), 
                    env.getDescription(), env.getEnvEntryType());    
                for (EnvironmentEntry envEntry : 
                    descriptor.getEnvironmentEntrySet()) {
                    if (envEntry.getName().equals(newEnvEntry.getName())) {
                        descriptor.removeEnvironmentEntry(envEntry);
                        break;
                    }
                 }
                 descriptor.addEnvironmentEntry(newEnvEntry);
            }

            for (ContextParam cParam: c.getContextParam()) {
                ContextParameter newContextParam = new EnvironmentProperty(
                    cParam.getParamName(), cParam.getParamValue(), 
                    cParam.getDescription());    
                for (ContextParameter contextParam : 
                    descriptor.getContextParametersSet()) {
                    if (contextParam.getName().equals(
                        newContextParam.getName())) {
                        descriptor.removeContextParameter(contextParam);
                        break;
                    }
                }
                descriptor.addContextParameter(newContextParam);
            }
        }
    }
}
