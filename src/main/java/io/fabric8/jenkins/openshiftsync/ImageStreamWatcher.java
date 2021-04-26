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
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.IMAGESTREAM_TYPE;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addPodTemplate;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.getPodTemplatesListFromImageStreams;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.hasPodTemplate;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.deleteAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.updateAgents;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.List;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.client.OpenShiftClient;

public class ImageStreamWatcher extends BaseWatcher<ImageStream> {
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String namespace) {
        super(namespace);
    }

    @Override
    public int getResyncPeriodMilliseconds() {
        return GlobalPluginConfiguration.get().getImageStreamListInterval();
    }

    public void start() {
        logger.info("Now handling startup image streams for " + namespace + " !!");
        ImageStreamList imageStreams = null;
        String ns = this.namespace;
        try {
            logger.fine("listing ImageStream resources");
            imageStreams = getAuthenticatedOpenShiftClient().imageStreams().inNamespace(ns).list();
            onImageStreamInitialization(imageStreams);
            logger.fine("handled ImageStream resources");
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
        }
        try {
            String rv = "0";
            if (imageStreams == null) {
                logger.warning("Unable to get image stream list; impacts resource version used for watch");
            } else {
                rv = imageStreams.getMetadata().getResourceVersion();
            }
            if (this.watch == null) {
                synchronized (this.lock) {
                    if (this.watch == null) {
                        logger.info("creating ImageStream watch for namespace " + ns + " and resource version " + rv);
                        OpenShiftClient client = getOpenshiftClient();
                        this.watch = client.imageStreams().inNamespace(ns).withResourceVersion(rv).watch(this);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
        }
    }

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
            start();
        }
    }

    @Override
    public void eventReceived(Action action, ImageStream imageStream) {
        String ns = this.namespace;
        if (imageStream != null) {
            try {
                List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(imageStream);
                ObjectMeta metadata = imageStream.getMetadata();
                String uid = metadata.getUid();
                String name = metadata.getName();
                String namespace = metadata.getNamespace();
                switch (action) {
                case ADDED:
                    addAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                    break;
                case MODIFIED:
                    updateAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                    break;
                case DELETED:
                    deleteAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                    break;
                case ERROR:
                    logger.warning("watch for imageStream " + ns + "/" + name + " received error event ");
                    break;
                default:
                    logger.warning("watch for imageStream " + ns + "/" + name + " received unknown event " + action);
                    break;
                }
            } catch (Exception e) {
                logger.log(WARNING, "Caught: " + e, e);
            }
        } else {
            logger.log(SEVERE, "Received event with null imagestream " + ns + " and Action: " + action + "...ignoring");
        }
    }

    private void onImageStreamInitialization(ImageStreamList imageStreams) {
        if (imageStreams != null) {
            List<ImageStream> items = imageStreams.getItems();
            if (items != null) {
                for (ImageStream imageStream : items) {
                    try {
                        List<PodTemplate> agents = getPodTemplatesListFromImageStreams(imageStream);
                        for (PodTemplate entry : agents) {
                            // watch event might beat the timer - put call is technically fine, but not
                            // addPodTemplate given k8s plugin issues
                            if (!hasPodTemplate(entry)) {
                                addPodTemplate(entry);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to update job", e);
                    }
                }
            }
        }
    }
}