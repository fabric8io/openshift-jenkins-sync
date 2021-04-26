package io.fabric8.jenkins.openshiftsync;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupStores;
import static com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL;
import static hudson.Util.fixNull;
import static hudson.util.Secret.fromString;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_CERTIFICATE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_CLIENT_TOKEN;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_FILENAME;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_PASSPHRASE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_PASSWORD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_SECRET_TEXT;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_USERNAME;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_TYPE_BASICAUTH;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_TYPE_OPAQUE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_TYPE_SSH;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.apache.commons.lang.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openshift.jenkins.plugins.OpenShiftTokenCredentials;

import hudson.model.Fingerprint;
import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;

public class CredentialsUtils {

    private static final String SECRET_TEXT_SECRET_TYPE = "secretText";
    private static final String FILE_SECRET_TYPE = "filename";
    private static final String TOKEN_SECRET_TYPE = "token";
    private static final Decoder DECODER = Base64.getDecoder();
    private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());
    private final static Map<String, String> SOURCE_SECRET_TO_CREDS_MAP = new ConcurrentHashMap<String, String>();
    public static final String KUBERNETES_SERVICE_ACCOUNT = "Kubernetes Service Account";
    public final static ConcurrentHashMap<String, String> UID_TO_SECRET_MAP = new ConcurrentHashMap<String, String>();

    public static Secret getSourceSecretForBuildConfig(BuildConfig buildConfig) {
        BuildConfigSpec spec = buildConfig.getSpec();
        if (spec != null) {
            BuildSource source = spec.getSource();
            if (source != null) {
                LocalObjectReference sourceSecret = source.getSourceSecret();
                if (sourceSecret != null) {
                    String sourceSecretName = sourceSecret.getName();
                    if (sourceSecretName != null && !sourceSecretName.isEmpty()) {
                        ObjectMeta buildConfigMetadata = buildConfig.getMetadata();
                        String namespace = buildConfigMetadata.getNamespace();
                        String name = buildConfigMetadata.getName();
                        logger.info("Retrieving SourceSecret for BuildConfig " + name + " in Namespace " + namespace);
                        OpenShiftClient client = getAuthenticatedOpenShiftClient();
                        Secret secret = client.secrets().inNamespace(namespace).withName(sourceSecretName).get();
                        if (secret != null) {
                            return secret;
                        } else {
                            String message = "Secret Name provided in BuildConfig " + name + " as " + sourceSecretName;
                            message += " does not exist. Please review the BuildConfig and make the necessary changes.";
                            logger.warning(message);
                        }
                    }
                }
            }
        }
        return null;
    }

    public static String updateSourceCredentials(BuildConfig buildConfig) throws IOException {
        String credentialsName = null;
        Secret sourceSecret = getSourceSecretForBuildConfig(buildConfig);
        if (sourceSecret != null) {
            ObjectMeta sourceSecretMetadata = sourceSecret.getMetadata();
            if (sourceSecretMetadata != null) {
                String namespace = sourceSecretMetadata.getNamespace();
                String secretName = sourceSecretMetadata.getName();
                ObjectMeta buildConfigMetadata = buildConfig.getMetadata();
                String buildConfigName = buildConfigMetadata.getName();
                credentialsName = insertOrUpdateCredentialsFromSecret(sourceSecret);
                String buildConfigAsString = NamespaceName.create(buildConfig).toString();
                if (credentialsName != null) {
                    logger.info("Linking sourceSecret " + secretName + " to Jenkins Credentials " + credentialsName);
                    linkSourceSecretToCredentials(buildConfigAsString, credentialsName);
                    return credentialsName;
                } else {
                    // call delete and remove any credential that fits the project/bcname pattern
                    logger.info("Unlinking BuildConfig sourceSecret matching BuildConfig " + buildConfigName);
                    credentialsName = unlinkBCSecretToCrendential(buildConfigAsString);
                    if (credentialsName != null) {
                        logger.info("Deleting sourceSecret " + secretName + " in namespace " + namespace);
                        String resourceVersion = buildConfigMetadata.getResourceVersion();
                        deleteCredential(credentialsName, NamespaceName.create(buildConfig), resourceVersion);
                    }
                }
            }
        }
        return credentialsName;
    }

    public static void deleteSourceCredentials(BuildConfig buildConfig) throws IOException {
        Secret sourceSecret = getSourceSecretForBuildConfig(buildConfig);
        if (sourceSecret != null) {
            ObjectMeta metadata = sourceSecret.getMetadata();
            if (metadata != null) {
                Map<String, String> labels = metadata.getLabels();
                if (labels != null) {
                    String labelValue = labels.get(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC);
                    boolean watching = labelValue != null && labelValue.equalsIgnoreCase(Constants.VALUE_SECRET_SYNC);
                    // for a bc delete, if we are watching this secret, do not delete
                    // credential until secret is actually deleted
                    if (watching)
                        return;
                    deleteCredential(sourceSecret);
                }
            }
        }
    }

    private static String getSecretCustomName(Secret secret) {
        ObjectMeta metadata = secret.getMetadata();
        if (metadata != null) {
            Map<String, String> annotations = metadata.getAnnotations();
            if (annotations != null) {
                String secretName = annotations.get(Annotations.SECRET_NAME);
                if (secretName != null) {
                    return secretName;
                }
            }
        }
        return null;
    }

    /**
     * Inserts or creates a Jenkins Credential for the given Secret
     * 
     * @param secret the secret to insert
     * @return the insert secret name
     * @throws IOException when the update of the secret fails
     */
    public static String upsertCredential(Secret secret) throws IOException {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                return insertOrUpdateCredentialsFromSecret(secret);
            }
        }
        return null;
    }

    private static String insertOrUpdateCredentialsFromSecret(Secret secret) throws IOException {
        if (secret != null) {
            String customSecretName = getSecretCustomName(secret);
            ObjectMeta metadata = secret.getMetadata();
            String namespace = metadata.getNamespace();
            String secretName = metadata.getName();
            Credentials creds = secretToCredentials(secret);
            if (creds != null) {
                // checking with updated secret name if custom name is not null
                String id = generateCredentialsName(namespace, secretName, customSecretName);
                Credentials existingCreds = lookupCredentials(id);
                final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
                try {
                    CredentialsStore creentialsStore = lookupStores(Jenkins.getActiveInstance()).iterator().next();
                    String originalId = generateCredentialsName(namespace, secretName, null);
                    Credentials existingOriginalCreds = lookupCredentials(originalId);
                    NamespaceName secretNamespaceName = null;

                    String secretUid = metadata.getUid();
                    if (!originalId.equals(id)) {
                        boolean hasAddedCredential = creentialsStore.addCredentials(Domain.global(), creds);
                        if (!hasAddedCredential) {
                            logger.warning("Setting secret  failed for secret with new Id " + id + " from Secret "
                                    + secretNamespaceName + " with revision: " + metadata.getResourceVersion());
                            logger.warning("Check if Id " + id + " is not already used.");
                        } else {
                            String oldId = UID_TO_SECRET_MAP.get(secretUid);
                            if (oldId != null) {
                                Credentials oldCredentials = lookupCredentials(oldId);
                                creentialsStore.removeCredentials(Domain.global(), oldCredentials);
                            } else if (existingOriginalCreds != null) {
                                creentialsStore.removeCredentials(Domain.global(), existingOriginalCreds);
                            }
                            UID_TO_SECRET_MAP.put(secretUid, id);
                            secretNamespaceName = NamespaceName.create(secret);
                            logger.info("Updated credential " + oldId + " with new Id " + id + " from Secret "
                                    + secretNamespaceName + " with revision: " + metadata.getResourceVersion());
                        }
                    } else {
                        if (existingCreds != null) {
                            creentialsStore.updateCredentials(Domain.global(), existingCreds, creds);
                            UID_TO_SECRET_MAP.put(secretUid, id);
                            secretNamespaceName = NamespaceName.create(secret);
                            logger.info("Updated credential " + id + " from Secret " + secretNamespaceName
                                    + " with revision: " + metadata.getResourceVersion());
                        } else {
                            boolean hasAddedCredential = creentialsStore.addCredentials(Domain.global(), creds);
                            if (!hasAddedCredential) {
                                logger.warning("Update failed for secret with new Id " + id + " from Secret "
                                        + secretNamespaceName + " with revision: " + metadata.getResourceVersion());
                            } else {
                                UID_TO_SECRET_MAP.put(secretUid, id);
                                secretNamespaceName = NamespaceName.create(secret);
                                logger.info("Created credential " + id + " from Secret " + secretNamespaceName
                                        + " with revision: " + metadata.getResourceVersion());
                            }
                        }
                    }
                    creentialsStore.save();
                } finally {
                    SecurityContextHolder.setContext(previousContext);
                }
                if (id != null && !id.isEmpty()) {
                    return id;
                }
            }
        }
        return null;
    }

    private static void deleteCredential(String id, NamespaceName name, String resourceRevision) throws IOException {
        Credentials existingCred = lookupCredentials(id);
        if (existingCred != null) {
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                Fingerprint fp = CredentialsProvider.getFingerprintOf(existingCred);
                if (fp != null && fp.getJobs().size() > 0) {
                    // per messages in credentials console, it is not a given but it is possible for
                    // job refs to a
                    // credential to be tracked ; if so, we will not prevent deletion, but at least
                    // note things for
                    // potential diagnostics
                    StringBuffer sb = new StringBuffer();
                    for (String job : fp.getJobs())
                        sb.append(job).append(" ");
                    logger.info("About to delete credential " + id + "which is referenced by jobs: " + sb.toString());
                }
                CredentialsStore s = CredentialsProvider.lookupStores(Jenkins.getActiveInstance()).iterator().next();
                if (!existingCred.getDescriptor().getDisplayName().contains(KUBERNETES_SERVICE_ACCOUNT)) {
                    s.removeCredentials(Domain.global(), existingCred);
                    logger.info("Deleted credential " + id + " from Secret " + name + " with revision: "
                            + resourceRevision);
                    s.save();
                } else {
                    logger.warning(
                            "Stopped attempt to delete " + KUBERNETES_SERVICE_ACCOUNT + " credentials with Id " + id);
                }
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }
    }

    public static void deleteCredential(Secret secret) {
        if (secret != null) {
            String id = generateCredentialsName(secret.getMetadata().getNamespace(), secret.getMetadata().getName(),
                    getSecretCustomName(secret));
            try {
                deleteCredential(id, NamespaceName.create(secret), secret.getMetadata().getResourceVersion());
            } catch (IOException e) {
                logger.log(SEVERE, "Credentials has not been deleted: " + e, e);
                throw new RuntimeException(e);
            }
        }
    }

    // getCurrentToken returns the ServiceAccount token currently selected by
    // the user. A return value of empty string
    // implies no token is configured.
    public static String getCurrentToken() {
        String credentialsId = GlobalPluginConfiguration.get().getCredentialsId();
        if (credentialsId.equals("")) {
            return "";
        }

        OpenShiftToken token = CredentialsMatchers
                .firstOrNull(
                        CredentialsProvider.lookupCredentials(OpenShiftToken.class, Jenkins.getActiveInstance(),
                                ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                        CredentialsMatchers.withId(credentialsId));

        if (token != null) {
            return token.getToken();
        }

        return "";
    }

    private static Credentials lookupCredentials(String id) {
        return CredentialsMatchers
                .firstOrNull(
                        CredentialsProvider.lookupCredentials(Credentials.class, Jenkins.getActiveInstance(),
                                ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                        CredentialsMatchers.withId(id));
    }

    private static String generateCredentialsName(String namespace, String name, String customName) {
        return (customName == null) ? namespace + "-" + name : customName;
    }

    private static Credentials arbitraryKeyValueTextCredential(Map<String, String> data,
            String generatedCredentialsName) {
        String text = "";
        if (data != null && data.size() > 0) {
            // convert to JSON for parsing ease in pipelines
            try {
                text = new ObjectMapper().writeValueAsString(data);
            } catch (JsonProcessingException e) {
                logger.log(Level.WARNING,
                        "Arbitrary opaque secret " + generatedCredentialsName + " had issue converting json", e);
            }
        }
        if (StringUtils.isBlank(text)) {
            logger.log(Level.WARNING,
                    "Opaque secret {0} did not provide any data that could be processed into a Jenkins credential",
                    new Object[] { generatedCredentialsName });

            return null;
        }
        return newSecretTextCredential(generatedCredentialsName,
                new String(Base64.getEncoder().encode(text.getBytes())));
    }

    private static Credentials secretToCredentials(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();
        String name = secret.getMetadata().getName();
        Map<String, String> data = secret.getData();
        if (data == null) {
            logger.log(WARNING, "Secret " + name + " does not contain any data. No credential will be created.");
            return null;
        }

        String generatedCredentialsName = generateCredentialsName(namespace, name, getSecretCustomName(secret));
        String passwordData = data.get(OPENSHIFT_SECRETS_DATA_PASSWORD);
        String sshKeyData = data.get(OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY);
        String usernameData = data.get(OPENSHIFT_SECRETS_DATA_USERNAME);
        // We support "passphrase" and "password" for the ssh passphrase; passphrase has
        // precedence over password
        String passphraseData = data.get(OPENSHIFT_SECRETS_DATA_PASSPHRASE);
        String sshPassphrase = isNotBlank(passphraseData) ? passphraseData : passwordData;

        switch (secret.getType()) {
        case OPENSHIFT_SECRETS_TYPE_OPAQUE:
            if (isNotBlank(usernameData) && isNotBlank(passwordData)) {
                return newUsernamePasswordCredentials(generatedCredentialsName, usernameData, passwordData);
            }
            if (isNotBlank(sshKeyData)) {
                return newSSHUserCredential(generatedCredentialsName, usernameData, sshKeyData, sshPassphrase);
            }
            String fileData = data.get(OPENSHIFT_SECRETS_DATA_FILENAME);
            if (isNotBlank(fileData)) {
                return newSecretFileCredential(generatedCredentialsName, fileData);
            }
            String certificateData = data.get(OPENSHIFT_SECRETS_DATA_CERTIFICATE);
            if (isNotBlank(certificateData)) {
                return newCertificateCredential(generatedCredentialsName, passwordData, certificateData);
            }
            String secretTextData = data.get(OPENSHIFT_SECRETS_DATA_SECRET_TEXT);
            if (isNotBlank(secretTextData)) {
                return newSecretTextCredential(generatedCredentialsName, secretTextData);
            }
            String openshiftTokenData = data.get(OPENSHIFT_SECRETS_DATA_CLIENT_TOKEN);
            if (isNotBlank(openshiftTokenData)) {
                return newOpenshiftTokenCredentials(generatedCredentialsName, openshiftTokenData);
            }
            return arbitraryKeyValueTextCredential(data, generatedCredentialsName);

        case OPENSHIFT_SECRETS_TYPE_BASICAUTH:
            return newUsernamePasswordCredentials(generatedCredentialsName, usernameData, passwordData);
        case OPENSHIFT_SECRETS_TYPE_SSH:
            return newSSHUserCredential(generatedCredentialsName, usernameData, sshKeyData, sshPassphrase);
        default:
            // the type field is marked optional in k8s.io/api/core/v1/types.go,
            // default to OPENSHIFT_SECRETS_DATA_SECRET_TEXT in this case
            return arbitraryKeyValueTextCredential(data, generatedCredentialsName);
        }
    }

    private static Credentials newOpenshiftTokenCredentials(String secretName, String secretText) {
        if (secretName == null || secretName.length() == 0 || secretText == null || secretText.length() == 0) {
            logInvalidSecretData(secretName, secretText, TOKEN_SECRET_TYPE);
            return null;
        }
        return new OpenShiftTokenCredentials(GLOBAL, secretName, secretName,
                fromString(new String(DECODER.decode(secretText), UTF_8)));
    }

    private static Credentials newSecretFileCredential(String secretName, String fileData) {
        if (secretName == null || secretName.length() == 0 || fileData == null || fileData.length() == 0) {
            logInvalidSecretData(secretName, fileData, FILE_SECRET_TYPE);
            return null;
        }
        return new FileCredentialsImpl(GLOBAL, secretName, secretName, secretName, SecretBytes.fromString(fileData));
    }

    private static Credentials newSecretTextCredential(String secretName, String secretText) {
        if (secretName == null || secretName.length() == 0 || secretText == null || secretText.length() == 0) {
            logInvalidSecretData(secretName, secretText, SECRET_TEXT_SECRET_TYPE);
            return null;
        }
        String data = new String(DECODER.decode(secretText), UTF_8);
        return new StringCredentialsImpl(GLOBAL, secretName, secretName, fromString(data));
    }

    private static Credentials newCertificateCredential(String secretName, String passwordData,
            String certificateData) {
        if (secretName == null || secretName.length() == 0 || certificateData == null
                || certificateData.length() == 0) {
            logInvalidSecretData(secretName, certificateData, "certificate");
            return null;
        }
        String certificatePassword = passwordData != null ? new String(DECODER.decode(passwordData)) : null;
        return new CertificateCredentialsImpl(GLOBAL, secretName, secretName, certificatePassword,
                new CertificateCredentialsImpl.UploadedKeyStoreSource(SecretBytes.fromString(certificateData)));
    }

    private static void logInvalidSecretData(String secretName, String secretText, String secretType) {
        logger.log(Level.WARNING,
                "Invalid secret data, secretName: " + secretName + " " + secretType + " is null: "
                        + (secretText == null) + " " + secretType + " is empty: "
                        + (secretText != null ? secretText.length() == 0 : false));
    }

    private static Credentials newSSHUserCredential(String secretName, String username, String sshKeyData,
            String passwordData) {
        boolean secretNameIsBlank = StringUtils.isBlank(secretName);
        boolean sshKeyDataIsBlank = StringUtils.isBlank(sshKeyData);
        if (secretNameIsBlank || sshKeyDataIsBlank) {
            logger.log(WARNING, "Invalid secret data, secretName: " + secretName + " sshKeyData is blank null: "
                    + sshKeyDataIsBlank);
            return null;
        }
        String sshKeyPassword = (passwordData != null) ? new String(DECODER.decode(passwordData), UTF_8) : null;
        String sshKey = new String(DECODER.decode(sshKeyData), UTF_8);
        String sshUser = fixNull(username).isEmpty() ? "" : new String(DECODER.decode(username), UTF_8);
        BasicSSHUserPrivateKey.DirectEntryPrivateKeySource key = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(
                sshKey);
        return new BasicSSHUserPrivateKey(GLOBAL, secretName, sshUser, key, sshKeyPassword, secretName);
    }

    private static Credentials newUsernamePasswordCredentials(String secretName, String usernameData,
            String passwordData) {
        if (secretName == null || secretName.length() == 0 || usernameData == null || usernameData.length() == 0
                || passwordData == null || passwordData.length() == 0) {
            logger.log(WARNING,
                    "Invalid secret data, secretName: " + secretName + " usernameData is null: "
                            + (usernameData == null) + " usernameData is empty: "
                            + (usernameData != null ? usernameData.length() == 0 : false) + " passwordData is null: "
                            + (passwordData == null) + " passwordData is empty: "
                            + (passwordData != null ? passwordData.length() == 0 : false));
            return null;

        }
        return new UsernamePasswordCredentialsImpl(GLOBAL, secretName, secretName,
                new String(DECODER.decode(usernameData), UTF_8), new String(DECODER.decode(passwordData), UTF_8));
    }

    /**
     * Does our configuration have credentials?
     *
     * @return true if found.
     */
    public static boolean hasCredentials() {
        return !StringUtils.isEmpty(getAuthenticatedOpenShiftClient().getConfiguration().getOauthToken());
    }

    static void linkSourceSecretToCredentials(String bc, String credential) {
        SOURCE_SECRET_TO_CREDS_MAP.put(bc, credential);
    }

    static String unlinkBCSecretToCrendential(String bc) {
        return SOURCE_SECRET_TO_CREDS_MAP.remove(bc);
    }

}
