/**
 * Copyright (C) 2017 Red Hat, Inc.
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
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.CONFIGMAP;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

public class ConfigMapClusterInformer implements ResourceEventHandler<ConfigMap>, Lifecyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private SharedIndexInformer<ConfigMap> informer;
    private Set<String> namespaces;

    public ConfigMapClusterInformer(String[] namespaces) {
        this.namespaces = new HashSet<>(Arrays.asList(namespaces));
    }

    public int getListIntervalInSeconds() {
        return 1_000 * GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public void start() {
        LOGGER.info("Starting cluster wide configMap informer for {} !!" + namespaces);
        LOGGER.debug("listing ConfigMap resources");
        SharedInformerFactory factory = getInformerFactory();
        this.informer = factory.sharedIndexInformerFor(ConfigMap.class, getListIntervalInSeconds());
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("ConfigMap informer started for namespaces: {}" + namespaces);
//        ConfigMapList list = getOpenshiftClient().configMaps().inNamespace(namespace).list();
//        onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping configMap informer {} !!" + namespaces);
        this.informer.stop();
    }

    @Override
    public void onAdd(ConfigMap obj) {
        LOGGER.debug("ConfigMap informer received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String name = metadata.getName();
                LOGGER.info("ConfigMap informer received add event for: {}" + name);
                List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
                String uid = metadata.getUid();
                PodTemplateUtils.addAgents(podTemplates, CONFIGMAP, uid, name, namespace);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    @Override
    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
        LOGGER.debug("ConfigMap informer  received update event for: {} to: {}" + oldObj + newObj);
        if (oldObj != null) {
            ObjectMeta oldMetadata = oldObj.getMetadata();
            String namespace = oldMetadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String oldRv = oldMetadata != null ? oldMetadata.getResourceVersion() : null;
                ObjectMeta newMetadata = newObj.getMetadata();
                String newResourceVersion = newMetadata != null ? newMetadata.getResourceVersion() : null;
                LOGGER.info("Update event received resource versions: {} to: {}" + oldRv + newResourceVersion);
                List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(newObj);
                ObjectMeta metadata = newMetadata;
                String uid = metadata.getUid();
                String name = metadata.getName();
                LOGGER.info("ConfigMap informer received update event for: {}", name);
                PodTemplateUtils.updateAgents(podTemplates, CONFIGMAP, uid, name, namespace);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    @Override
    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
        LOGGER.debug("ConfigMap informer received delete event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
                String uid = metadata.getUid();
                String name = metadata.getName();
                PodTemplateUtils.deleteAgents(podTemplates, CONFIGMAP, uid, name, namespace);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    private void onInit(List<ConfigMap> list) {
        if (list != null) {
            for (ConfigMap configMap : list) {
                PodTemplateUtils.addPodTemplateFromConfigMap(configMap);
            }
        }
    }

    private void waitInformerSync(SharedIndexInformer<ConfigMap> informer) {
        while (!informer.hasSynced()) {
            LOGGER.info("Waiting informer to sync for " + namespaces);
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                LOGGER.info("Interrupted waiting thread: " + e);
            }
        }
    }
}
