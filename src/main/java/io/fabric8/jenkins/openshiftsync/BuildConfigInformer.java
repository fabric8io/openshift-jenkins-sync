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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.BuildConfig;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
public class BuildConfigInformer implements ResourceEventHandler<BuildConfig>, Lifecyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private SharedIndexInformer<BuildConfig> informer;
    private String namespace;

    public BuildConfigInformer(String namespace) {
        this.namespace = namespace;
    }

    public int getResyncPeriodMilliseconds() {
        return 1_000 * GlobalPluginConfiguration.get().getBuildConfigListInterval();
    }

    public void start() {
        LOGGER.info("Starting BuildConfig informer for {} !!" + namespace);
        LOGGER.debug("listing BuildConfig resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        this.informer = factory.sharedIndexInformerFor(BuildConfig.class, getResyncPeriodMilliseconds());
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("BuildConfig informer started for namespace: {}" + namespace);
        // BuildConfigList list =
        // getOpenshiftClient().buildConfigs().inNamespace(namespace).list();
        // onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping secret informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(BuildConfig obj) {
        LOGGER.debug("BuildConfig informer  received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("BuildConfig informer received add event for: {}" + name);
            try {
                BuildConfigWatcher.upsertJob(obj);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onUpdate(BuildConfig oldObj, BuildConfig newObj) {
        LOGGER.debug("BuildConfig informer received update event for: {} to: {}" + oldObj + " " + newObj);
        if (newObj != null) {
            String oldRv = oldObj.getMetadata().getResourceVersion();
            String newRv = newObj.getMetadata().getResourceVersion();
            LOGGER.info("BuildConfig informer received update event for: {} to: {}" + oldRv + " " + newRv);
            try {
                BuildConfigWatcher.modifyEventToJenkinsJob(newObj);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDelete(BuildConfig obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("BuildConfig informer received delete event for: {}" + obj);
        if (obj != null) {
            try {
                BuildConfigWatcher.deleteEventToJenkinsJob(obj);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private void onInit(List<BuildConfig> list) {
        for (BuildConfig buildConfig : list) {
            try {
                BuildConfigWatcher.upsertJob(buildConfig);
            } catch (Exception e) {
                LOGGER.error("Failed to update job", e);
            }
        }
        // poke the BuildWatcher builds with no BC list and see if we
        // can create job
        // runs for premature builds
        BuildManager.flushBuildsWithNoBCList();
    }

}
