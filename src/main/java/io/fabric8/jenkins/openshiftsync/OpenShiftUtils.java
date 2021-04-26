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

import static io.fabric8.jenkins.openshiftsync.BuildPhases.NEW;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.RUNNING;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_DEFAULT_NAMESPACE;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.tools.ant.filters.StringInputStream;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import hudson.BulkChange;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerStatus;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.Version;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import jenkins.model.Jenkins;
import okhttp3.Dispatcher;

/**
 */
public class OpenShiftUtils {

    private final static Logger logger = Logger.getLogger(OpenShiftUtils.class.getName());

    private static OpenShiftClient openShiftClient;
    private static String jenkinsPodNamespace = null;
    private static final Jenkins JENKINS_INSTANCE = Jenkins.getInstanceOrNull();

    static {
        jenkinsPodNamespace = System.getProperty(Constants.OPENSHIFT_PROJECT_ENV_VAR_NAME);
        if (jenkinsPodNamespace != null && jenkinsPodNamespace.trim().length() > 0) {
            jenkinsPodNamespace = jenkinsPodNamespace.trim();
        } else {
            File f = new File(Constants.OPENSHIFT_PROJECT_FILE);
            if (f.exists()) {
                FileReader fr = null;
                BufferedReader br = null;
                try {
                    fr = new FileReader(Constants.OPENSHIFT_PROJECT_FILE);
                    br = new BufferedReader(fr);
                    // should just be one line
                    jenkinsPodNamespace = br.readLine();
                    if (jenkinsPodNamespace != null && jenkinsPodNamespace.trim().length() > 0) {
                        jenkinsPodNamespace = jenkinsPodNamespace.trim();
                    }

                } catch (FileNotFoundException e) {
                    logger.log(Level.FINE, "getNamespaceFromPodInputs", e);
                } catch (IOException e) {
                    logger.log(Level.FINE, "getNamespaceFromPodInputs", e);
                } finally {
                    try {
                        br.close();
                        fr.close();
                    } catch (Throwable e) {
                        logger.log(Level.FINE, "getNamespaceFromPodInputs", e);
                    }
                }
            }
        }
    }

    private static final DateTimeFormatter dateFormatter = ISODateTimeFormat.dateTimeNoMillis();

    /**
     * Initializes an {@link OpenShiftClient}
     *
     * @param serverUrl the optional URL of where the OpenShift cluster API server
     *                  is running
     * @param maxConnections2 
     */
    public synchronized static void initializeOpenShiftClient(String serverUrl, int maxConnections) {
        if (openShiftClient != null) {
            logger.log(INFO, "Closing already initialized openshift client");
            openShiftClient.close();
        }
        OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
        if (serverUrl != null && !serverUrl.isEmpty()) {
            configBuilder.withMasterUrl(serverUrl);
        }
        Config config = configBuilder.build();
        logger.log(INFO, "Current OpenShift Client Configuration: " + ReflectionToStringBuilder.toString(config));
        
        String version = JENKINS_INSTANCE.getPluginManager().getPlugin("openshift-sync").getVersion();
        config.setUserAgent("openshift-sync-plugin-" + version + "/fabric8-" + Version.clientVersion());
        openShiftClient = new DefaultOpenShiftClient(config);
        logger.log(INFO, "New OpenShift client initialized: " + openShiftClient);

        DefaultOpenShiftClient defClient = (DefaultOpenShiftClient) openShiftClient;
        Dispatcher dispatcher = defClient.getHttpClient().dispatcher();
//        int maxConnections = 100;//GlobalPluginConfiguration.get().getMaxConnections();
        dispatcher.setMaxRequestsPerHost(maxConnections);
        dispatcher.setMaxRequests(maxConnections);
    }

    public synchronized static OpenShiftClient getOpenShiftClient() {
        return openShiftClient;
    }

    // Get the current OpenShiftClient and configure to use the current Oauth
    // token.
    public synchronized static OpenShiftClient getAuthenticatedOpenShiftClient() {
        if (openShiftClient == null) {
            GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
            initializeOpenShiftClient(config.getServer(), config.getMaxConnections());
        }
        if (openShiftClient != null) {
            String token = CredentialsUtils.getCurrentToken();
            if (token.length() > 0) {
                openShiftClient.getConfiguration().setOauthToken(token);
            }
        }
        return openShiftClient;
    }

    public static SharedInformerFactory getInformerFactory() {
        return getAuthenticatedOpenShiftClient().informers();
/*        if (factory == null) {
            synchronized (lock) {
                factory = getAuthenticatedOpenShiftClient().informers();
            }
        }
        return factory;*/
    }

    public synchronized static void shutdownOpenShiftClient() {
        logger.info("Stopping openshift client: " + openShiftClient);
        if (openShiftClient != null) {
            
            // All this stuff is done by  openShiftClient.close();
            
//            DefaultOpenShiftClient client = (DefaultOpenShiftClient) openShiftClient;
//            Dispatcher dispatcher = client.getHttpClient().dispatcher();
//            ExecutorService executorService = dispatcher.executorService();
//            try {
//                dispatcher.cancelAll();
//                client.getHttpClient().connectionPool().evictAll();
//                //TODO Akram: shutting donw the executorService prevents other informers to re-attach to it.
//                executorService.shutdown();
//                TimeUnit.SECONDS.sleep(1);
//            } catch (Exception e) {
//                logger.warning("Error while stopping executor thread");
//                executorService.shutdownNow();
//            }
            openShiftClient.close();
            openShiftClient = null;
//            factory = null;
        }
    }

    /**
     * Checks if a {@link BuildConfig} relates to a Jenkins build
     *
     * @param bc the BuildConfig
     * @return true if this is an OpenShift BuildConfig which should be mirrored to
     *         a Jenkins Job
     */
    public static boolean isPipelineStrategyBuildConfig(BuildConfig bc) {
        if (BuildConfigToJobMapper.JENKINS_PIPELINE_BUILD_STRATEGY
                .equalsIgnoreCase(bc.getSpec().getStrategy().getType())
                && bc.getSpec().getStrategy().getJenkinsPipelineStrategy() != null) {
            return true;
        }

        ObjectMeta metadata = bc.getMetadata();
        if (metadata != null) {
            Map<String, String> annotations = metadata.getAnnotations();
            if (annotations != null) {
                if (annotations.get("fabric8.link.jenkins.job/label") != null) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean isPipelineStrategyBuild(Build b) {
        if (b.getSpec() == null) {
            logger.warning("bad input, null spec: " + b);
            return false;
        }
        if (b.getSpec().getStrategy() == null) {
            logger.warning("bad input, null strategy: " + b);
            return false;
        }
        if (BuildConfigToJobMapper.JENKINS_PIPELINE_BUILD_STRATEGY.equalsIgnoreCase(b.getSpec().getStrategy().getType())
                && b.getSpec().getStrategy().getJenkinsPipelineStrategy() != null) {
            return true;
        }
        return false;
    }

    /**
     * Finds the Jenkins job name for the given {@link BuildConfig}.
     *
     * @param bc the BuildConfig
     * @return the jenkins job name for the given BuildConfig
     */
    public static String jenkinsJobName(BuildConfig bc) {
        String namespace = bc.getMetadata().getNamespace();
        String name = bc.getMetadata().getName();
        return jenkinsJobName(namespace, name);

    }

    /**
     * Creates the Jenkins Job name for the given buildConfigName
     *
     * @param namespace       the namespace of the build
     * @param buildConfigName the name of the {@link BuildConfig} in in the
     *                        namespace
     * @return the jenkins job name for the given namespace and name
     */
    public static String jenkinsJobName(String namespace, String buildConfigName) {
        return namespace + "-" + buildConfigName;
    }

    /**
     * Finds the full jenkins job path including folders for the given
     * {@link BuildConfig}.
     *
     * @param bc the BuildConfig
     * @return the jenkins job name for the given BuildConfig
     */
    public static String jenkinsJobFullName(BuildConfig bc) {
        String jobName = getAnnotation(bc, Annotations.JENKINS_JOB_PATH);
        if (StringUtils.isNotBlank(jobName)) {
            return jobName;
        }
        if (GlobalPluginConfiguration.get().getFoldersEnabled()) {
            return getNamespace(bc) + "/" + jenkinsJobName(getNamespace(bc), getName(bc));
        } else {
            return getName(bc);
        }
    }

    /**
     * Returns the parent for the given item full name or default to the active
     * jenkins if it does not exist
     * 
     * @param activeJenkins the active Jenkins instance
     * @param fullName      the full name of the instance
     * @param namespace     the namespace where the instance runs
     * @return and ItemGroup representing the full parent
     */
    public static ItemGroup getFullNameParent(Jenkins activeJenkins, String fullName, String namespace) {
        int idx = fullName.lastIndexOf('/');
        if (idx > 0) {
            String parentFullName = fullName.substring(0, idx);
            Item parent = activeJenkins.getItemByFullName(parentFullName);
            if (parent instanceof ItemGroup) {
                return (ItemGroup) parent;
            } else if (parentFullName.equals(namespace)) {

                // lets lazily create a new folder for this namespace parent
                Folder folder = new Folder(activeJenkins, namespace);
                try {
                    folder.setDescription("Folder for the OpenShift project: " + namespace);
                } catch (IOException e) {
                    // ignore
                }
                BulkChange bk = new BulkChange(folder);
                InputStream jobStream = new StringInputStream(new XStream2().toXML(folder));
                try {
                    activeJenkins.createProjectFromXML(namespace, jobStream).save();
                } catch (IOException e) {
                    logger.warning("Failed to create the Folder: " + namespace);
                }
                try {
                    bk.commit();
                } catch (IOException e) {
                    logger.warning("Failed to commit toe BulkChange for the Folder: " + namespace);
                }
                // lets look it up again to be sure
                parent = activeJenkins.getItemByFullName(namespace);
                if (parent instanceof ItemGroup) {
                    return (ItemGroup) parent;
                }
            }
        }
        return activeJenkins;
    }

    /**
     * Finds the Jenkins job display name for the given {@link BuildConfig}.
     *
     * @param bc the BuildConfig
     * @return the jenkins job display name for the given BuildConfig
     */
    public static String jenkinsJobDisplayName(BuildConfig bc) {
        String namespace = bc.getMetadata().getNamespace();
        String name = bc.getMetadata().getName();
        return jenkinsJobDisplayName(namespace, name);
    }

    /**
     * Creates the Jenkins Job display name for the given buildConfigName
     *
     * @param namespace       the namespace of the build
     * @param buildConfigName the name of the {@link BuildConfig} in in the
     *                        namespace
     * @return the jenkins job display name for the given namespace and name
     */
    public static String jenkinsJobDisplayName(String namespace, String buildConfigName) {
        return namespace + "/" + buildConfigName;
    }

    /**
     * Gets the current namespace running Jenkins inside or returns a reasonable
     * default
     *
     * @param configuredNamespaces the optional configured namespace(s)
     * @param client               the OpenShift client
     * @return the default namespace using either the configuration value, the
     *         default namespace on the client or "default"
     */
    public static String[] getNamespaceOrUseDefault(String[] configuredNamespaces, OpenShiftClient client) {
        String[] namespaces = configuredNamespaces;

        if (namespaces != null) {
            for (int i = 0; i < namespaces.length; i++) {
                if (namespaces[i].startsWith("${") && namespaces[i].endsWith("}")) {
                    String envVar = namespaces[i].substring(2, namespaces[i].length() - 1);
                    namespaces[i] = System.getenv(envVar);
                    if (StringUtils.isBlank(namespaces[i])) {
                        logger.warning("No value defined for namespace environment variable `" + envVar + "`");
                    }
                }
            }
        }
        if (namespaces == null || namespaces.length == 0) {
            namespaces = new String[] { client.getNamespace() };
            if (StringUtils.isBlank(namespaces[0])) {
                namespaces = new String[] { OPENSHIFT_DEFAULT_NAMESPACE };
            }
        }
        return namespaces;
    }

    /**
     * Returns the public URL of the given service
     *
     * @param openShiftClient     the OpenShiftClient to use
     * @param defaultProtocolText the protocol text part of a URL such as
     *                            <code>http://</code>
     * @param namespace           the Kubernetes namespace
     * @param serviceName         the service name
     * @return the external URL of the service
     */
    public static String getExternalServiceUrl(OpenShiftClient openShiftClient, String defaultProtocolText,
            String namespace, String serviceName) {
        if (namespace != null && serviceName != null) {
            try {
                RouteList routes = openShiftClient.routes().inNamespace(namespace).list();
                for (Route route : routes.getItems()) {
                    RouteSpec spec = route.getSpec();
                    if (spec != null && spec.getTo() != null && "Service".equalsIgnoreCase(spec.getTo().getKind())
                            && serviceName.equalsIgnoreCase(spec.getTo().getName())) {
                        String host = spec.getHost();
                        if (host != null && host.length() > 0) {
                            if (spec.getTls() != null) {
                                return "https://" + host;
                            }
                            return "http://" + host;
                        }
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
            }
            // lets try the portalIP instead
            try {
                Service service = openShiftClient.services().inNamespace(namespace).withName(serviceName).get();
                if (service != null) {
                    ServiceSpec spec = service.getSpec();
                    if (spec != null) {
                        String host = spec.getClusterIP();
                        if (host != null && host.length() > 0) {
                            return defaultProtocolText + host;
                        }
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
            }
        }

        // lets default to the service DNS name
        return defaultProtocolText + serviceName;
    }

    /**
     * Calculates the external URL to access Jenkins
     *
     * @param namespace       the namespace Jenkins is runing inside
     * @param openShiftClient the OpenShift client
     * @return the external URL to access Jenkins
     */
    public static String getJenkinsURL(OpenShiftClient openShiftClient, String namespace) {
        // if the user has explicitly configured the jenkins root URL, use it
        String rootUrl = Jenkins.getInstance().getRootUrl();
        if (StringUtils.isNotEmpty(rootUrl)) {
            return rootUrl;
        }

        // otherwise, we'll see if we are running in a pod and can infer it from
        // the service/route
        // TODO we will eventually make the service name configurable, with the
        // default of "jenkins"
        return getExternalServiceUrl(openShiftClient, "http://", namespace, "jenkins");
    }

    public static String getNamespacefromPodInputs() {
        return jenkinsPodNamespace;
    }

    /**
     * Lazily creates the GitSource if need be then updates the git URL
     * 
     * @param buildConfig the BuildConfig to update
     * @param gitUrl      the URL to the git repo
     * @param ref         the git ref (commit/branch/etc) for the build
     */
    public static void updateGitSourceUrl(BuildConfig buildConfig, String gitUrl, String ref) {
        BuildConfigSpec spec = buildConfig.getSpec();
        if (spec == null) {
            spec = new BuildConfigSpec();
            buildConfig.setSpec(spec);
        }
        BuildSource source = spec.getSource();
        if (source == null) {
            source = new BuildSource();
            spec.setSource(source);
        }
        source.setType("Git");
        GitBuildSource gitSource = source.getGit();
        if (gitSource == null) {
            gitSource = new GitBuildSource();
            source.setGit(gitSource);
        }
        gitSource.setUri(gitUrl);
        gitSource.setRef(ref);
    }

    public static void updateOpenShiftBuildPhase(Build build, String phase) {
        String ns = build.getMetadata().getNamespace();
        String name = build.getMetadata().getName();
        logger.log(FINE, "setting build to {0} in namespace {1}/{2}", new Object[] { phase, ns, name });

        BuildBuilder builder = new BuildBuilder(build).editStatus().withPhase(phase).endStatus();
        getAuthenticatedOpenShiftClient().builds().inNamespace(ns).withName(name).edit(b -> builder.build());
    }

    /**
     * Maps a Jenkins Job name to an ObjectShift BuildConfig name
     *
     * @return the namespaced name for the BuildConfig
     * @param jobName   the job to associate to a BuildConfig name
     * @param namespace the default namespace that Jenkins is running inside
     */
    public static NamespaceName buildConfigNameFromJenkinsJobName(String jobName, String namespace) {
        // TODO lets detect the namespace separator in the jobName for cases
        // where a jenkins is used for
        // BuildConfigs in multiple namespaces?
        return new NamespaceName(namespace, jobName);
    }

    public static long parseResourceVersion(HasMetadata obj) {
        return parseResourceVersion(obj.getMetadata().getResourceVersion());
    }

    public static long parseResourceVersion(String resourceVersion) {
        try {
            return Long.parseLong(resourceVersion);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatTimestamp(long timestamp) {
        return dateFormatter.print(new DateTime(timestamp));
    }

    public static long parseTimestamp(String timestamp) {
        return dateFormatter.parseMillis(timestamp);
    }

    public static boolean isResourceWithoutStateEqual(HasMetadata oldObj, HasMetadata newObj) {
        try {
            byte[] oldDigest = MessageDigest.getInstance("MD5")
                    .digest(dumpWithoutRuntimeStateAsYaml(oldObj).getBytes(StandardCharsets.UTF_8));
            byte[] newDigest = MessageDigest.getInstance("MD5")
                    .digest(dumpWithoutRuntimeStateAsYaml(newObj).getBytes(StandardCharsets.UTF_8));
            return Arrays.equals(oldDigest, newDigest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String dumpWithoutRuntimeStateAsYaml(HasMetadata obj) throws JsonProcessingException {
        ObjectMapper statelessMapper = new ObjectMapper(new YAMLFactory());
        statelessMapper.addMixInAnnotations(ObjectMeta.class, ObjectMetaMixIn.class);
        statelessMapper.addMixInAnnotations(ReplicationController.class, StatelessReplicationControllerMixIn.class);
        return statelessMapper.writeValueAsString(obj);
    }

    public static boolean isCancellable(BuildStatus buildStatus) {
        String phase = buildStatus.getPhase();
        return phase.equals(NEW) || phase.equals(PENDING) || phase.equals(RUNNING);
    }

    public static boolean isNew(BuildStatus buildStatus) {
        return buildStatus.getPhase().equals(NEW);
    }

    public static boolean isCancelled(BuildStatus status) {
        return status != null && status.getCancelled() != null && Boolean.TRUE.equals(status.getCancelled());
    }

    /**
     * Lets convert the string to btw a valid kubernetes resource name
     */
    static String convertNameToValidResourceName(String text) {
        String lower = text.toLowerCase();
        StringBuilder builder = new StringBuilder();
        boolean started = false;
        char lastCh = ' ';
        for (int i = 0, last = lower.length() - 1; i <= last; i++) {
            char ch = lower.charAt(i);
            if (!(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9')) {
                if (ch == '/') {
                    ch = '.';
                } else if (ch != '.' && ch != '-') {
                    ch = '-';
                }
                if (!started || lastCh == '-' || lastCh == '.' || i == last) {
                    continue;
                }
            }
            builder.append(ch);
            started = true;
            lastCh = ch;
        }
        return builder.toString();
    }

    public static String getAnnotation(HasMetadata resource, String name) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            Map<String, String> annotations = metadata.getAnnotations();
            if (annotations != null) {
                return annotations.get(name);
            }
        }
        return null;
    }

    public static void addAnnotation(HasMetadata resource, String name, String value) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata == null) {
            metadata = new ObjectMeta();
            resource.setMetadata(metadata);
        }
        Map<String, String> annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            metadata.setAnnotations(annotations);
        }
        annotations.put(name, value);
    }

    public static String getNamespace(HasMetadata resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            return metadata.getNamespace();
        }
        return null;
    }

    public static String getName(HasMetadata resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            return metadata.getName();
        }
        return null;
    }

    protected static OpenShiftClient getOpenshiftClient() {
        return getAuthenticatedOpenShiftClient();
    }

    abstract class StatelessReplicationControllerMixIn extends ReplicationController {
        @JsonIgnore
        private ReplicationControllerStatus status;

        StatelessReplicationControllerMixIn() {
        }

        @JsonIgnore
        public abstract ReplicationControllerStatus getStatus();
    }

    abstract class ObjectMetaMixIn extends ObjectMeta {
        @JsonIgnore
        private String creationTimestamp;
        @JsonIgnore
        private String deletionTimestamp;
        @JsonIgnore
        private Long generation;
        @JsonIgnore
        private String resourceVersion;
        @JsonIgnore
        private String selfLink;
        @JsonIgnore
        private String uid;

        ObjectMetaMixIn() {
        }

        @JsonIgnore
        public abstract String getCreationTimestamp();

        @JsonIgnore
        public abstract String getDeletionTimestamp();

        @JsonIgnore
        public abstract Long getGeneration();

        @JsonIgnore
        public abstract String getResourceVersion();

        @JsonIgnore
        public abstract String getSelfLink();

        @JsonIgnore
        public abstract String getUid();
    }
}
