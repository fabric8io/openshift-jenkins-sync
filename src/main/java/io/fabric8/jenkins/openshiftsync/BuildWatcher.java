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

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.NEW;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.handleBuildList;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isPipelineStrategyBuild;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.client.OpenShiftClient;

public class BuildWatcher extends BaseWatcher<Build> {
    private static final Logger logger = Logger.getLogger(BuildWatcher.class.getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BuildWatcher(String namespace) {
        super(namespace);
    }

    @Override
    public int getResyncPeriodMilliseconds() {
        return GlobalPluginConfiguration.get().getBuildListInterval();
    }

    public void start() {
        BuildToActionMapper.initialize();
        logger.info("Now handling startup build for " + namespace + " !!");

        // prior to finding new builds poke the BuildWatcher builds with
        // no BC list and see if we
        // can create job runs for premature builds we already know
        // about
        BuildManager.flushBuildsWithNoBCList();
        String ns = this.namespace;
        BuildList newBuilds = null;
        try {
            logger.fine("listing Build resources");
            OpenShiftClient client = getAuthenticatedOpenShiftClient();
            newBuilds = client.builds().inNamespace(ns).withField(OPENSHIFT_BUILD_STATUS_FIELD, NEW).list();
            onInitialBuilds(newBuilds);
            logger.fine("handled Build resources");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
        }
        try {
            String rv = "0";
            if (newBuilds == null) {
                logger.warning("Unable to get build list; impacts resource version used for watch");
            } else {
                rv = newBuilds.getMetadata().getResourceVersion();
            }

            if (this.watch == null) {
                synchronized (this.lock) {
                    if (this.watch == null) {
                        logger.info("creating Build watch for namespace " + ns + " and resource version " + rv);
                        OpenShiftClient client = getOpenshiftClient();
                        this.watch = client.builds().inNamespace(ns).withResourceVersion(rv).watch(this);
                    }
                }
            }
        } catch (KubernetesClientException e) {
            logger.log(SEVERE, "Failed to load initial Builds: " + e, e);
            this.watch.close();
            Status status = e.getStatus();
            String message = status != null ? status.getMessage() : "Unknown status on query";
            // TODO add a throttling mechancism to wait before retying in loop
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e1) {
            }
            this.onClose(new WatcherException(message, e));
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load initial Builds: " + e, e);
        }
        BuildManager.reconcileRunsAndBuilds();
    }

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
            start();
        }
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    @Override
    public void eventReceived(Action action, Build build) {
        if (build == null) {
            logger.warning("Received  event with null Build: " + action + ", ignoring: " + this);
            return;
        }
        if (isPipelineStrategyBuild(build)) {
            try {
                String name = build.getMetadata().getName();
                switch (action) {
                case ADDED:
                    BuildManager.addEventToJenkinsJobRun(build);
                    break;
                case MODIFIED:
                    BuildManager.modifyEventToJenkinsJobRun(build);
                    break;
                case DELETED:
                    BuildManager.deleteEventToJenkinsJobRun(build);
                    break;
                case ERROR:
                    logger.warning("watch for build " + name + " received error event ");
                    break;
                default:
                    logger.warning("watch for build " + name + " received unknown event " + action);
                    break;
                }
            } catch (Exception e) {
                logger.log(WARNING, "Caught: " + e, e);
            }
        }
    }

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
                        BuildManager.addBuildToNoBCList(b);
                    }
                    continue;
                }
                BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
                if (bcp == null) {
                    List<Build> builds = buildConfigBuilds.getValue();
                    for (Build b : builds) {
                        logger.info("skipping listed new build " + b.getMetadata().getName() + " no prop at this time");
                        BuildManager.addBuildToNoBCList(b);
                    }
                    continue;
                }
                List<Build> builds = buildConfigBuilds.getValue();
                handleBuildList(job, builds, bcp);
            }
        }
    }

}
