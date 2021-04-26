package io.fabric8.jenkins.openshiftsync;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToJobMap {

    private final static Logger logger = Logger.getLogger(BuildConfigToJobMap.class.getName());
    private static ConcurrentHashMap<String, WorkflowJob> buildConfigToJobMap = new ConcurrentHashMap<String, WorkflowJob>();

    private BuildConfigToJobMap() {
    }

    static synchronized void initializeBuildConfigToJobMap() {
        if (buildConfigToJobMap == null) {
            List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
            buildConfigToJobMap = new ConcurrentHashMap<>(jobs.size());
            for (WorkflowJob job : jobs) {
                BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);
                if (property != null) {
                    String namespace = property.getNamespace();
                    String name = property.getName();
                    if (isNotBlank(namespace) && isNotBlank(name)) {
                        String jenkinsJobName = jenkinsJobName(namespace, name);
                        buildConfigToJobMap.put(jenkinsJobName, job);
                    }
                }
            }
        }
    }

    static WorkflowJob getJobFromBuildConfig(BuildConfig buildConfig) {
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            return null;
        }
        return getJobFromBuildConfigNameNamespace(meta.getName(), meta.getNamespace());
    }

    static WorkflowJob getJobFromBuildConfigNameNamespace(String name, String namespace) {
        if (isBlank(name) || isBlank(namespace)) {
            return null;
        }
        return buildConfigToJobMap.get(OpenShiftUtils.jenkinsJobName(namespace, name));
    }

    static void putJobWithBuildConfig(WorkflowJob job, BuildConfig buildConfig) {
        if (buildConfig == null) {
            throw new IllegalArgumentException("BuildConfig cannot be null");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException("BuildConfig must contain valid metadata");
        }
        putJobWithBuildConfigNameNamespace(job, meta.getName(), meta.getNamespace());
    }

    static void putJobWithBuildConfigNameNamespace(WorkflowJob job, String name, String namespace) {
        if (isBlank(name) || isBlank(namespace)) {
            throw new IllegalArgumentException("BuildConfig name and namespace must not be blank");
        }
        buildConfigToJobMap.put(OpenShiftUtils.jenkinsJobName(namespace, name), job);
    }

    static void removeJobWithBuildConfig(BuildConfig buildConfig) {
        if (buildConfig == null) {
            throw new IllegalArgumentException("BuildConfig cannot be null");
        }
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException("BuildConfig must contain valid metadata");
        }
        removeJobWithBuildConfigNameNamespace(meta.getName(), meta.getNamespace());
    }

    static void removeJobWithBuildConfigNameNamespace(String name, String namespace) {
        if (isBlank(name) || isBlank(namespace)) {
            throw new IllegalArgumentException("BuildConfig name/namepsace must not be blank");
        }
        buildConfigToJobMap.remove(OpenShiftUtils.jenkinsJobName(namespace, name));
    }

}
