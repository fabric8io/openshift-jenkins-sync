/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import static hudson.security.ACL.SYSTEM;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.shutdownOpenShiftClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static jenkins.model.Jenkins.ADMINISTER;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfiguration.class.getName());

    private boolean enabled = true;
    private boolean foldersEnabled = true;
    private boolean useClusterMode = false;
    private boolean syncConfigMaps = true;
    private boolean syncSecrets = true;
    private boolean syncImageStreams = true;
    private boolean syncBuildConfigsAndBuilds = true;

    private String server;
    private String credentialsId = "";
    private int maxConnections = 100;

    private String[] namespaces;
    private String jobNamePattern;
    private String skipOrganizationPrefix;
    private String skipBranchSuffix;
    private int buildListInterval = 300;
    private int buildConfigListInterval = 300;
    private int secretListInterval = 300;
    private int configMapListInterval = 300;
    private int imageStreamListInterval = 300;

    private static GlobalPluginConfigurationTimerTask TASK;
    private static ScheduledFuture<?> FUTURE;

    @DataBoundConstructor
    public GlobalPluginConfiguration(boolean enable, String server, String namespace, boolean foldersEnabled,
            String credentialsId, String jobNamePattern, String skipOrganizationPrefix, String skipBranchSuffix,
            int buildListInterval, int buildConfigListInterval, int configMapListInterval, int secretListInterval,
            int imageStreamListInterval, boolean useClusterMode, boolean syncConfigMaps, boolean syncSecrets,
            boolean syncImageStreams, boolean syncBuildsConfigAndBuilds, int maxConnections) {
        this.enabled = enable;
        this.server = server;
        this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
        this.foldersEnabled = foldersEnabled;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.jobNamePattern = jobNamePattern;
        this.skipOrganizationPrefix = skipOrganizationPrefix;
        this.skipBranchSuffix = skipBranchSuffix;
        this.buildListInterval = buildListInterval;
        this.buildConfigListInterval = buildConfigListInterval;
        this.configMapListInterval = configMapListInterval;
        this.secretListInterval = secretListInterval;
        this.imageStreamListInterval = imageStreamListInterval;
        this.useClusterMode = useClusterMode;
        this.syncConfigMaps = syncConfigMaps;
        this.syncSecrets = syncSecrets;
        this.syncImageStreams = syncImageStreams;
        this.syncBuildConfigsAndBuilds = syncBuildsConfigAndBuilds;
        this.maxConnections = maxConnections;
        configChange();
    }

    public GlobalPluginConfiguration() {
        load();
        configChange();
        // save();
    }

    public static GlobalPluginConfiguration get() {
        return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
    }

    private synchronized void configChange() {
        logger.info("OpenShift Sync Plugin processing a newly supplied configuration");
        stop();
//        shutdownOpenShiftClient();
        start();
    }

    private void start() {
        if (this.enabled) {
            OpenShiftUtils.initializeOpenShiftClient(this.server, this.maxConnections);
            this.namespaces = getNamespaceOrUseDefault(this.namespaces, getOpenShiftClient());
            if (TASK != null) {
                logger.warning("Previously existing configuration task");
            }
            TASK = new GlobalPluginConfigurationTimerTask(this.namespaces);
            FUTURE = Timer.get().schedule(TASK, 1, SECONDS); // lets give jenkins a while to get started ;)
        } else {
            logger.info("OpenShift Sync Plugin has been disabled");
        }
    }

    private void stop() {
        if (FUTURE != null) {
            boolean interrupted = FUTURE.cancel(true);
            if (interrupted) {
                logger.info("OpenShift Sync Plugin task has been interrupted");
            }
        }
        if (TASK != null) {
            TASK.stop();
            TASK.cancel();
            TASK = null;
        }
        OpenShiftUtils.shutdownOpenShiftClient();
    }

    /**
     * Validates OpenShift Sync Configuration form by chec
     */
    @POST
    public FormValidation doValidate(@QueryParameter("useClusterMode") final boolean useClusterMode,
            @QueryParameter("syncConfigMaps") final boolean syncConfigMaps,
            @QueryParameter("syncSecrets") final boolean syncSecrets,
            @QueryParameter("syncImageStreams") final boolean syncImageStreams,
            @QueryParameter("syncBuildConfigsAndBuilds") final boolean syncBuildConfigsAndBuilds,
            @QueryParameter("maxConnections") final int maxConnections,
            @QueryParameter("namespace") final String namespace, @SuppressWarnings("rawtypes") @AncestorInPath Job job)
            throws IOException, ServletException {
        if (useClusterMode) {
            try {
                int secrets = getAuthenticatedOpenShiftClient().secrets().inAnyNamespace().list().getItems().size();
                logger.info("Cluster secrets: " + secrets);
            } catch (Exception e) {
                StringBuilder message = new StringBuilder();
                message.append("The ServiceAccount used by Jenkins does not have cluster wide watch permissions.\n");
                message.append("To use cluster mode, you need to run the following commands an restart Jenkins: \n\n");
                message.append("oc create clusterrole jenkins-watcher --verb=get,list,watch \\\n");
                message.append("    --resource=configmaps,builds,buildconfigs,imagestreams,secrets\n\n");
                message.append("oc adm policy add-cluster-role-to-user jenkins-watcher -z jenkins\n");
                logger.severe("Error while trying to query secrets lists: " + e);
                return FormValidation.error(message.toString());
            }
        } else {
            StringBuilder message = new StringBuilder();
            if (maxConnections > 200) {
                message.append("Cluster mode is recommended if max connections is greater than 200.");
            }
            int requiredConnectionsCount = 0;
            if (syncBuildConfigsAndBuilds) {
                requiredConnectionsCount += 2;
            }
            if (syncImageStreams) {
                requiredConnectionsCount++;
            }
            if (syncSecrets) {
                requiredConnectionsCount++;
            }
            if (syncConfigMaps) {
                requiredConnectionsCount++;
            }
            String[] namespaces = StringUtils.isBlank(namespace) ? new String[] {} : namespace.split(" ");
            int namespacesCount = namespaces.length;
            requiredConnectionsCount = namespacesCount * requiredConnectionsCount;
            if (maxConnections < requiredConnectionsCount) {
                message.append(String.format("Watching %s namespaces with your configuration requires %s connections.",
                        namespacesCount, requiredConnectionsCount));
            }
            if (message.length() > 0) {
                return FormValidation.warning(message.toString());
            }
        }
        return FormValidation.ok("Success");
    }

    @Override
    public String getDisplayName() {
        return "OpenShift Jenkins Sync";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
        req.bindJSON(this, json);
        configChange();
        save();
        return true;
    }

    // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
    // http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
    // https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
    public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return (ListBoxModel) null;
        }
        StandardListBoxModel model = new StandardListBoxModel();
        if (!jenkins.hasPermission(ADMINISTER)) {
            // Important! Otherwise you expose credentials metadata to random web requests.
            return model.includeCurrentValue(credentialsId);
        }
        return model.includeEmptyValue().includeAs(SYSTEM, jenkins, OpenShiftToken.class)
                .includeCurrentValue(credentialsId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    // When Jenkins is reset, credentialsId is strangely set to null. However,
    // credentialsId has no reason to be null.
    public String getCredentialsId() {
        return credentialsId == null ? "" : credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getNamespace() {
        return namespaces == null ? "" : StringUtils.join(namespaces, " ");
    }

    public void setNamespace(String namespace) {
        this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
    }

    public boolean getFoldersEnabled() {
        return foldersEnabled;
    }

    public void setFoldersEnabled(boolean foldersEnabled) {
        this.foldersEnabled = foldersEnabled;
    }

    public String getJobNamePattern() {
        return jobNamePattern;
    }

    public void setJobNamePattern(String jobNamePattern) {
        this.jobNamePattern = jobNamePattern;
    }

    public String getSkipOrganizationPrefix() {
        return skipOrganizationPrefix;
    }

    public void setSkipOrganizationPrefix(String skipOrganizationPrefix) {
        this.skipOrganizationPrefix = skipOrganizationPrefix;
    }

    public String getSkipBranchSuffix() {
        return skipBranchSuffix;
    }

    public void setSkipBranchSuffix(String skipBranchSuffix) {
        this.skipBranchSuffix = skipBranchSuffix;
    }

    public int getBuildListInterval() {
        return buildListInterval;
    }

    public void setBuildListInterval(int buildListInterval) {
        this.buildListInterval = buildListInterval;
    }

    public int getBuildConfigListInterval() {
        return buildConfigListInterval;
    }

    public void setBuildConfigListInterval(int buildConfigListInterval) {
        this.buildConfigListInterval = buildConfigListInterval;
    }

    public int getSecretListInterval() {
        return secretListInterval;
    }

    public void setSecretListInterval(int secretListInterval) {
        this.secretListInterval = secretListInterval;
    }

    public int getConfigMapListInterval() {
        return configMapListInterval;
    }

    public void setConfigMapListInterval(int configMapListInterval) {
        this.configMapListInterval = configMapListInterval;
    }

    public int getImageStreamListInterval() {
        return imageStreamListInterval;
    }

    public void setImageStreamListInterval(int imageStreamListInterval) {
        this.imageStreamListInterval = imageStreamListInterval;
    }

    String[] getNamespaces() {
        return namespaces;
    }

    void setNamespaces(String[] namespaces) {
        this.namespaces = namespaces;
    }

    public boolean isUseClusterMode() {
        return useClusterMode;
    }

    public void setUseClusterMode(boolean useClusterMode) {
        this.useClusterMode = useClusterMode;
    }

    public boolean isSyncConfigMaps() {
        return syncConfigMaps;
    }

    public void setSyncConfigMaps(boolean syncConfigMaps) {
        this.syncConfigMaps = syncConfigMaps;
    }

    public boolean isSyncSecrets() {
        return syncSecrets;
    }

    public void setSyncSecrets(boolean syncSecrets) {
        this.syncSecrets = syncSecrets;
    }

    public boolean isSyncImageStreams() {
        return syncImageStreams;
    }

    public void setSyncImageStreams(boolean syncImageStreams) {
        this.syncImageStreams = syncImageStreams;
    }

    public boolean isSyncBuildConfigsAndBuilds() {
        return syncBuildConfigsAndBuilds;
    }

    public void setSyncBuildConfigsAndBuilds(boolean syncBuildConfigsAndBuilds) {
        this.syncBuildConfigsAndBuilds = syncBuildConfigsAndBuilds;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

}
