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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.CONFIGMAP;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.deleteAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.updateAgents;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.List;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.openshift.client.OpenShiftClient;

public class ConfigMapWatcher extends BaseWatcher<ConfigMap> {
    private final Logger LOGGER = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String namespace) {
        super(namespace);
    }

    @Override
    public int getResyncPeriodMilliseconds() {
        return GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public void start() {
        LOGGER.info("Now handling startup config maps for " + namespace + " !!");
        ConfigMapList configMaps = null;
        String ns = this.namespace;
        try {
            LOGGER.fine("listing ConfigMap resources");
            OpenShiftClient client = getAuthenticatedOpenShiftClient();
            configMaps = client.configMaps().inNamespace(ns).list();
            onInitialConfigMaps(configMaps);
            LOGGER.fine("handled ConfigMap resources");
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
        }
        try {
            String rv = "0";
            if (configMaps == null) {
                LOGGER.warning("Unable to get config map list; impacts resource version used for watch");
            } else {
                rv = configMaps.getMetadata().getResourceVersion();
            }

            if (this.watch == null) {
                synchronized (this.lock) {
                    if (this.watch == null) {
                        LOGGER.info("creating ConfigMap watch for namespace " + ns + " and resource version " + rv);
                        OpenShiftClient client = getOpenshiftClient();
                        this.watch = client.configMaps().inNamespace(ns).withResourceVersion(rv).watch(this);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
        }

    }

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
            start();
        }
    }

    @Override
    public void eventReceived(Action action, ConfigMap configMap) {
        if (configMap == null) {
            LOGGER.warning("Received  event with null Build: " + action + ", ignoring: " + this);
            return;
        }
        try {
            List<PodTemplate> slavesFromCM = PodTemplateUtils.podTemplatesFromConfigMap(configMap);
            boolean hasSlaves = slavesFromCM.size() > 0;
            String uid = configMap.getMetadata().getUid();
            String cmname = configMap.getMetadata().getName();
            String namespace = configMap.getMetadata().getNamespace();
            switch (action) {
            case ADDED:
                if (hasSlaves) {
                    addAgents(slavesFromCM, CONFIGMAP, uid, cmname, namespace);
                }
                break;
            case MODIFIED:
                updateAgents(slavesFromCM, CONFIGMAP, uid, cmname, namespace);
                break;
            case DELETED:
                deleteAgents(slavesFromCM, CONFIGMAP, uid, cmname, namespace);
                break;
            case ERROR:
                LOGGER.warning("watch for configMap " + configMap.getMetadata().getName() + " received error event ");
                break;
            default:
                LOGGER.warning("watch for configMap " + configMap.getMetadata().getName() + " received unknown event "
                        + action);
                break;
            }
        } catch (Exception e) {
            LOGGER.log(WARNING, "Caught: " + e, e);
        }
    }

    private void onInitialConfigMaps(ConfigMapList configMaps) {
        if (configMaps == null)
            return;

        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    if (PodTemplateUtils.configMapContainsSlave(configMap)
                            && !PodTemplateUtils.trackedPodTemplates.containsKey(configMap.getMetadata().getUid())) {
                        List<PodTemplate> templates = PodTemplateUtils.podTemplatesFromConfigMap(configMap);
                        PodTemplateUtils.trackedPodTemplates.put(configMap.getMetadata().getUid(), templates);
                        for (PodTemplate podTemplate : templates) {
                            PodTemplateUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(SEVERE, "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

}
