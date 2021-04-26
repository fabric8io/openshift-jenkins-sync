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

import static io.fabric8.jenkins.openshiftsync.Annotations.BUILDCONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfigNameNamespace;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.cancelBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.deleteRun;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.handleBuildList;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.triggerJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancellable;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancelled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isNew;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

@SuppressWarnings({ "deprecation", "serial" })
public class BuildManager {
    private static final Logger logger = Logger.getLogger(BuildManager.class.getName());

    /**
     * now that listing interval is 5 minutes (used to be 10 seconds), we have seen
     * timing windows where if the build watch events come before build config watch
     * events when both are created in a simultaneous fashion, there is an up to 5
     * minute delay before the job run gets kicked off started seeing duplicate
     * builds getting kicked off so quit depending on so moved off of concurrent
     * hash set to concurrent hash map using namepace/name key
     */
    protected static final ConcurrentHashMap<String, Build> buildsWithNoBCList = new ConcurrentHashMap<String, Build>();

    public static void onInitialBuilds(BuildList buildList) {
        if (buildList == null)
            return;
        List<Build> items = buildList.getItems();
        if (items != null) {
            Collections.sort(items, new Comparator<Build>() {
                @Override
                public int compare(Build b1, Build b2) {
                    if (b1.getMetadata().getAnnotations() == null
                            || b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
                        logger.warning("cannot compare build " + b1.getMetadata().getName() + " from namespace "
                                + b1.getMetadata().getNamespace() + ", has bad annotations: "
                                + b1.getMetadata().getAnnotations());
                        return 0;
                    }
                    if (b2.getMetadata().getAnnotations() == null
                            || b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
                        logger.warning("cannot compare build " + b2.getMetadata().getName() + " from namespace "
                                + b2.getMetadata().getNamespace() + ", has bad annotations: "
                                + b2.getMetadata().getAnnotations());
                        return 0;
                    }
                    int rc = 0;
                    try {
                        rc = Long.compare(

                                Long.parseLong(
                                        b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
                                Long.parseLong(
                                        b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)));
                    } catch (Throwable t) {
                        logger.log(Level.FINE, "onInitialBuilds", t);
                    }
                    return rc;
                }
            });

            // We need to sort the builds into their build configs so we can
            // handle build run policies correctly.
            Map<String, BuildConfig> buildConfigMap = new HashMap<>();
            Map<BuildConfig, List<Build>> buildConfigBuildMap = new HashMap<>(items.size());
            for (Build b : items) {
                if (!OpenShiftUtils.isPipelineStrategyBuild(b))
                    continue;
                String buildConfigName = b.getStatus().getConfig().getName();
                if (StringUtils.isEmpty(buildConfigName)) {
                    continue;
                }
                String namespace = b.getMetadata().getNamespace();
                String bcMapKey = namespace + "/" + buildConfigName;
                BuildConfig bc = buildConfigMap.get(bcMapKey);
                if (bc == null) {
                    bc = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace)
                            .withName(buildConfigName).get();
                    if (bc == null) {
                        // if the bc is not there via a REST get, then it is not
                        // going to be, and we are not handling manual creation
                        // of pipeline build objects, so don't bother with "no bc list"
                        continue;
                    }
                    buildConfigMap.put(bcMapKey, bc);
                }
                List<Build> bcBuilds = buildConfigBuildMap.get(bc);
                if (bcBuilds == null) {
                    bcBuilds = new ArrayList<>();
                    buildConfigBuildMap.put(bc, bcBuilds);
                }
                bcBuilds.add(b);
            }

            // Now handle the builds.
            for (Map.Entry<BuildConfig, List<Build>> buildConfigBuilds : buildConfigBuildMap.entrySet()) {
                BuildConfig bc = buildConfigBuilds.getKey();
                if (bc.getMetadata() == null) {
                    // Should never happen but let's be safe...
                    continue;
                }
                WorkflowJob job = getJobFromBuildConfig(bc);
                if (job == null) {
                    List<Build> builds = buildConfigBuilds.getValue();
                    for (Build b : builds) {
                        logger.info("skipping listed new build " + b.getMetadata().getName() + " no job at this time");
                        addBuildToNoBCList(b);
                    }
                    continue;
                }
                BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
                if (bcp == null) {
                    List<Build> builds = buildConfigBuilds.getValue();
                    for (Build b : builds) {
                        logger.info("skipping listed new build " + b.getMetadata().getName() + " no prop at this time");
                        addBuildToNoBCList(b);
                    }
                    continue;
                }
                List<Build> builds = buildConfigBuilds.getValue();
                handleBuildList(job, builds, bcp);
            }
        }
    }

    static void modifyEventToJenkinsJobRun(Build build) {
        BuildStatus status = build.getStatus();
        if (status != null && isCancellable(status) && isCancelled(status)) {
            WorkflowJob job = getJobFromBuild(build);
            if (job != null) {
                cancelBuild(job, build);
            } else {
                removeBuildFromNoBCList(build);
            }
        } else {
            // see if any pre-BC cached builds can now be flushed
            flushBuildsWithNoBCList();
        }
    }

    public static boolean addEventToJenkinsJobRun(Build build) throws IOException {
        // should have been caught upstack, but just in case since public method
        if (!OpenShiftUtils.isPipelineStrategyBuild(build))
            return false;
        BuildStatus status = build.getStatus();
        if (status != null) {
            if (isCancelled(status)) {
                updateOpenShiftBuildPhase(build, CANCELLED);
                return false;
            }
            if (!isNew(status)) {
                return false;
            }
        }

        WorkflowJob job = getJobFromBuild(build);
        if (job != null) {
            return triggerJob(job, build);
        }
        logger.info("skipping watch event for build " + build.getMetadata().getName() + " no job at this time");
        addBuildToNoBCList(build);
        return false;
    }

    static void addBuildToNoBCList(Build build) {
        // should have been caught upstack, but just in case since public method
        if (!OpenShiftUtils.isPipelineStrategyBuild(build))
            return;
        try {
            buildsWithNoBCList.put(build.getMetadata().getNamespace() + build.getMetadata().getName(), build);
        } catch (ConcurrentModificationException | IllegalArgumentException | UnsupportedOperationException
                | NullPointerException e) {
            logger.log(Level.WARNING, "Failed to add item " + build.getMetadata().getName(), e);
        }
    }

    static void removeBuildFromNoBCList(Build build) {
        buildsWithNoBCList.remove(build.getMetadata().getNamespace() + build.getMetadata().getName());
    }

    // trigger any builds whose watch events arrived before the
    // corresponding build config watch events
    public static void flushBuildsWithNoBCList() {

        ConcurrentHashMap<String, Build> clone = null;
        synchronized (buildsWithNoBCList) {
            clone = new ConcurrentHashMap<String, Build>(buildsWithNoBCList);
        }
        boolean anyRemoveFailures = false;
        for (Build build : clone.values()) {
            WorkflowJob job = getJobFromBuild(build);
            if (job != null) {
                try {
                    logger.info("triggering job run for previously skipped build " + build.getMetadata().getName());
                    triggerJob(job, build);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "flushBuildsWithNoBCList", e);
                }
                try {
                    synchronized (buildsWithNoBCList) {
                        removeBuildFromNoBCList(build);
                    }
                } catch (Throwable t) {
                    // TODO
                    // concurrent mod exceptions are not suppose to occur
                    // with concurrent hash set; this try/catch with log
                    // and the anyRemoveFailures post processing is a bit
                    // of safety paranoia until this proves to be true
                    // over extended usage ... probably can remove at some
                    // point
                    anyRemoveFailures = true;
                    logger.log(Level.WARNING, "flushBuildsWithNoBCList", t);
                }
            }

            synchronized (buildsWithNoBCList) {
                if (anyRemoveFailures && buildsWithNoBCList.size() > 0) {
                    buildsWithNoBCList.clear();
                }

            }
        }
    }

    // innerDeleteEventToJenkinsJobRun is the actual delete logic at the heart
    // of deleteEventToJenkinsJobRun
    // that is either in a sync block or not based on the presence of a BC uid
    private static void innerDeleteEventToJenkinsJobRun(final Build build) throws Exception {
        final WorkflowJob job = getJobFromBuild(build);
        if (job != null) {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    cancelBuild(job, build, true);
                    return null;
                }
            });
        } else {
            // in case build was created and deleted quickly, prior to seeing BC
            // event, clear out from pre-BC cache
            removeBuildFromNoBCList(build);
        }
        deleteRun(job, build);
    }

    // in response to receiving an openshift delete build event, this method
    // will drive
    // the clean up of the Jenkins job run the build is mapped one to one with;
    // as part of that
    // clean up it will synchronize with the build config event watcher to
    // handle build config
    // delete events and build delete events that arrive concurrently and in a
    // nondeterministic
    // order
    static void deleteEventToJenkinsJobRun(final Build build) throws Exception {
        List<OwnerReference> ownerRefs = build.getMetadata().getOwnerReferences();
        String bcUid = null;
        for (OwnerReference ref : ownerRefs) {
            if ("BuildConfig".equals(ref.getKind()) && ref.getUid() != null && ref.getUid().length() > 0) {
                // employ intern to facilitate sync'ing on the same actual
                // object
                bcUid = ref.getUid().intern();
                synchronized (bcUid) {
                    // if entire job already deleted via bc delete, just return
                    if (getJobFromBuildConfigNameNamespace(getAnnotation(build, BUILDCONFIG_NAME),
                            build.getMetadata().getNamespace()) == null) {
                        return;
                    }
                    innerDeleteEventToJenkinsJobRun(build);
                    return;
                }
            }
        }
        // otherwise, if something odd is up and there is no parent BC, just
        // clean up
        innerDeleteEventToJenkinsJobRun(build);
    }

    /**
     * Reconciles Jenkins job runs and OpenShift builds
     *
     * Deletes all job runs that do not have an associated build in OpenShift
     */
    static void reconcileRunsAndBuilds() {
        logger.fine("Reconciling job runs and builds");
        List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
        for (WorkflowJob job : jobs) {
            BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);
            if (property != null) {
                String ns = property.getNamespace();
                String name = property.getName();
                if (StringUtils.isNotBlank(ns) && StringUtils.isNotBlank(name)) {
                    logger.fine("Checking job " + job + " runs for BuildConfig " + ns + "/" + name);
                    OpenShiftClient client = getAuthenticatedOpenShiftClient();
                    BuildList builds = client.builds().inNamespace(ns).withLabel("buildconfig=" + name).list();
                    for (WorkflowRun run : job.getBuilds()) {
                        boolean found = false;
                        BuildCause cause = run.getCause(BuildCause.class);
                        for (Build build : builds.getItems()) {
                            if (cause != null && cause.getUid().equals(build.getMetadata().getUid())) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            deleteRun(run);
                        }
                    }
                }
            }

        }
    }

}
