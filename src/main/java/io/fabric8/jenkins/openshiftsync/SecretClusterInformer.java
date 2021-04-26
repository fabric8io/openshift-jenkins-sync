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

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC;
import static io.fabric8.jenkins.openshiftsync.Constants.VALUE_SECRET_SYNC;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static java.util.Collections.singletonMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

public class SecretClusterInformer implements ResourceEventHandler<Secret>, Lifecyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretClusterInformer.class.getName());

    private final static ConcurrentHashMap<String, String> trackedSecrets = new ConcurrentHashMap<String, String>();

    private SharedIndexInformer<Secret> informer;
    private Set<String> namespaces;

    public SecretClusterInformer(String[] namespaces) {
        this.namespaces = new HashSet<>(Arrays.asList(namespaces));
    }

    public int getResyncPeriodMilliseconds() {
        return 1_000 * GlobalPluginConfiguration.get().getSecretListInterval();
    }

    public void start() {
        LOGGER.info("Starting cluster wide secret informer {} !!" + namespaces);
        LOGGER.debug("listing Secret resources");
        SharedInformerFactory factory = getInformerFactory();
        Map<String, String> labels = singletonMap(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC);
        OperationContext withLabels = new OperationContext().withLabels(labels);
        this.informer = factory.sharedIndexInformerFor(Secret.class, withLabels, getResyncPeriodMilliseconds());
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("Secret informer started for namespace: {}" + namespaces);
//        SecretList list = getOpenshiftClient().secrets().inNamespace(namespace).withLabels(labels).list();
//        onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping secret informer {} !!" + namespaces);
        this.informer.stop();
    }

    @Override
    public void onAdd(Secret obj) {
        LOGGER.debug("Secret informer  received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String name = metadata.getName();
                LOGGER.info("Secret informer received add event for: {}" + name);
                SecretManager.insertOrUpdateCredentialFromSecret(obj);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        LOGGER.debug("Secret informer received update event for: {} to: {}" + oldObj + newObj);
        if (oldObj != null) {
            ObjectMeta metadata = oldObj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String name = metadata.getName();
                LOGGER.info("Secret informer received update event for: {}", name);
                SecretManager.updateCredential(newObj);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        LOGGER.debug("Secret informer received delete event for: {}", obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String name = obj.getMetadata().getName();
                LOGGER.info("Secret informer received delete event for: {}", name);
                CredentialsUtils.deleteCredential(obj);
            } else {
                LOGGER.debug("Received event for a namespace we are not watching: {} ... ignoring", namespace);
            }
        }
    }

    private void onInit(List<Secret> list) {
        for (Secret secret : list) {
            try {
                if (SecretManager.validSecret(secret) && SecretManager.shouldProcessSecret(secret)) {
                    SecretManager.insertOrUpdateCredentialFromSecret(secret);
                    trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to update secred", e);
            }
        }
    }

}
