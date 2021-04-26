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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;

public class BuildInformer implements ResourceEventHandler<Build>, Lifecyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private final static BuildComparator BUILD_COMPARATOR = new BuildComparator();
    private SharedIndexInformer<Build> informer;
    private String namespace;

    public BuildInformer(String namespace) {
        this.namespace = namespace;
    }

    /**
     * now that listing interval is 5 minutes (used to be 10 seconds), we have seen
     * timing windows where if the build watch events come before build config watch
     * events when both are created in a simultaneous fashion, there is an up to 5
     * minutes delay before the job run gets kicked off started seeing duplicate
     * builds getting kicked off so quit depending on so moved off of concurrent
     * hash set to concurrent hash map using namepace/name key
     */
    public int getResyncPeriodMilliseconds() {
        return 1_000 * GlobalPluginConfiguration.get().getBuildListInterval();
    }

    public void start() {
        LOGGER.info("Starting Build informer for {} !!" + namespace);
        LOGGER.debug("Listing Build resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        this.informer = factory.sharedIndexInformerFor(Build.class, getResyncPeriodMilliseconds());
        this.informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("Build informer started for namespace: {}" + namespace);
//        BuildList list = getOpenshiftClient().builds().inNamespace(namespace).list();
//        onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping Builder informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(Build obj) {
        LOGGER.debug("Build informer  received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("Build informer received add event for: {}" + name);
            try {
                BuildManager.addEventToJenkinsJobRun(obj);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onUpdate(Build oldObj, Build newObj) {
        LOGGER.debug("Build informer received update event for: {} to: {}" + oldObj + " " + newObj);
        if (newObj != null) {
            String oldRv = oldObj.getMetadata().getResourceVersion();
            String newRv = newObj.getMetadata().getResourceVersion();
            LOGGER.info("Build informer received update event for: {} to: {}" + oldRv + " " + newRv);
            BuildManager.modifyEventToJenkinsJobRun(newObj);
        }
    }

    @Override
    public void onDelete(Build obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("Build informer received delete event for: {}" + obj);
        if (obj != null) {
            try {
                BuildManager.deleteEventToJenkinsJobRun(obj);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private static void onInit(List<Build> list) {
        Collections.sort(list, BUILD_COMPARATOR);
        // We need to sort the builds into their build configs so we can
        // handle build run policies correctly.
        Map<String, BuildConfig> buildConfigMap = new HashMap<>();
        Map<BuildConfig, List<Build>> buildConfigBuildMap = new HashMap<>(list.size());
//        BuildManager.mapBuildToBuildConfigs(list, buildConfigMap, buildConfigBuildMap);
//        BuildManager.mapBuildsToBuildConfigs(buildConfigBuildMap);
        BuildManager.reconcileRunsAndBuilds();
    }

}
