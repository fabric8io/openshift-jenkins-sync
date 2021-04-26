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

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC;
import static io.fabric8.jenkins.openshiftsync.Constants.VALUE_SECRET_SYNC;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.openshift.client.OpenShiftClient;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 */
public class SecretWatcher extends BaseWatcher<Secret> {
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecretWatcher(String namespace) {
        super(namespace);
    }

    @Override
    public int getResyncPeriodMilliseconds() {
        return GlobalPluginConfiguration.get().getSecretListInterval();
    }

    public void start() {
        // lets process the initial state
        // super.start();
        logger.info("Now handling startup secrets for " + namespace + " !!");
        SecretList secrets = null;
        String ns = this.namespace;
        try {
            logger.fine("listing Secrets resources");
            secrets = getAuthenticatedOpenShiftClient().secrets().inNamespace(ns)
                    .withLabel(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, Constants.VALUE_SECRET_SYNC).list();
            SecretManager.onInitialSecrets(secrets);
            logger.fine("handled Secrets resources");
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load Secrets: " + e, e);
        }
        try {
            String rv = "0";
            if (secrets == null) {
                logger.warning("Unable to get secret list; impacts resource version used for watch");
            } else {
                rv = secrets.getMetadata().getResourceVersion();
            }

            if (this.watch == null) {
                synchronized (this.lock) {
                    if (this.watch == null) {
                        logger.info("creating Secret watch for namespace " + ns + " and resource version" + rv);
                        OpenShiftClient client = getOpenshiftClient();
                        this.watch = client.secrets().inNamespace(ns)
                                .withLabel(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC)
                                .withResourceVersion(rv).watch(this);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load Secrets: " + e, e);
        }
    }

    
    
   

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    @Override
    public void eventReceived(Action action, Secret secret) {
        if (secret == null) {
            logger.warning("Received  event with null Secret: " + action + ", ignoring: " + this);
            return;
        }
        try {
            switch (action) {
            case ADDED:
                SecretManager.insertOrUpdateCredentialFromSecret(secret);
                break;
            case DELETED:
                SecretManager.deleteCredential(secret);
                break;
            case MODIFIED:
                SecretManager.updateCredential(secret);
                break;
            case ERROR:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning(
                        "watch for secret " + secret.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

   

  
    
}
