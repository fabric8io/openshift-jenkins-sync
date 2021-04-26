package io.fabric8.jenkins.openshiftsync;

import static java.util.logging.Level.SEVERE;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;

public class SecretManager {

    private final static Logger logger = Logger.getLogger(SecretManager.class.getName());
    private final static ConcurrentHashMap<String, String> trackedSecrets = new ConcurrentHashMap<>();

    public static void insertOrUpdateCredentialFromSecret(final Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Upserting Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret)) {
                    try {
                        CredentialsUtils.upsertCredential(secret);
                        trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                    } catch (IOException e) {
                        logger.log(SEVERE, "Credential has not been saved: " + e, e);
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    static void onInitialSecrets(SecretList secrets) {
        if (secrets == null)
            return;
        List<Secret> items = secrets.getItems();
        if (items != null) {
            for (Secret secret : items) {
                try {
                    if (validSecret(secret) && shouldProcessSecret(secret)) {
                        insertOrUpdateCredentialFromSecret(secret);
                        trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    protected static void updateCredential(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Modifying Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret) && shouldProcessSecret(secret)) {
                    try {
                        CredentialsUtils.upsertCredential(secret);
                        trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                    } catch (IOException e) {
                        logger.log(SEVERE, "Secret has not been saved: " + e, e);
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    protected static boolean validSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String name = metadata.getName();
                String namespace = metadata.getNamespace();
                logger.info("Validating Secret with Uid " + metadata.getUid() + " with Name " + name);
                return name != null && !name.isEmpty() && namespace != null && !namespace.isEmpty();
            }
        }
        return false;
    }

    protected static boolean shouldProcessSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String uid = metadata.getUid();
                String rv = metadata.getResourceVersion();
                String oldResourceVersion = trackedSecrets.get(uid);
                if (oldResourceVersion == null || !oldResourceVersion.equals(rv)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void deleteCredential(final Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                trackedSecrets.remove(metadata.getUid());
                CredentialsUtils.deleteCredential(secret);
            }
        }
    }

}
