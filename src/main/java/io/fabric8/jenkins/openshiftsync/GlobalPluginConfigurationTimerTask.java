package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import hudson.init.InitMilestone;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;

public class GlobalPluginConfigurationTimerTask extends SafeTimerTask {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfigurationTimerTask.class.getName());
    private String[] namespaces;
    private final static List<Lifecyclable> informers = new ArrayList<>();

    public GlobalPluginConfigurationTimerTask(String[] namespaces) {
        this.namespaces = namespaces;
    }

    @Override
    protected void doRun() throws Exception {
        logger.info("Confirming Jenkins is started");
        waitForJenkinsStartup();
        stop();
        start();
    }

    private void start() {
        if (GlobalPluginConfiguration.get().isUseClusterMode()) {
            startClusterInformers();
            logger.info("All the cluster informers have been registered!! ... starting all registered informers");
        } else {
            startNamespaceInformers();
            logger.info("All the namespaced informers have been registered!! ... starting all registered informers");
        }
        getInformerFactory().startAllRegisteredInformers();
        logger.info("All registered informers have been started");

    }

    private void waitForJenkinsStartup() {
        while (true) {
            @SuppressWarnings("deprecation")
            final Jenkins instance = Jenkins.getActiveInstance();
            // We can look at Jenkins Init Level to see if we are ready to start. If we do
            // not wait, we risk the chance of a deadlock.
            InitMilestone initLevel = instance.getInitLevel();
            logger.fine("Jenkins init level: " + initLevel);
            if (initLevel == COMPLETED) {
                break;
            }
            logger.info("Jenkins not ready...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logger.info("Interrupted while sleeping");
            }
        }
    }

    private void startNamespaceInformers() {
        for (String namespace : namespaces) {
            GlobalPluginConfiguration configuration = GlobalPluginConfiguration.get();
            if (configuration.isSyncBuildConfigsAndBuilds()) {
                BuildConfigInformer buildConfigInformer = new BuildConfigInformer(namespace);
                informers.add(buildConfigInformer);
                buildConfigInformer.start();

                BuildInformer buildInformer = new BuildInformer(namespace);
                buildInformer.start();
                informers.add(buildInformer);
            }
            if (configuration.isSyncConfigMaps()) {
                ConfigMapInformer configMapInformer = new ConfigMapInformer(namespace);
                configMapInformer.start();
                informers.add(configMapInformer);
            }
            if (configuration.isSyncImageStreams()) {
                ImageStreamInformer imageStreamInformer = new ImageStreamInformer(namespace);
                imageStreamInformer.start();
                informers.add(imageStreamInformer);
            }
            if (configuration.isSyncSecrets()) {
                SecretInformer secretInformer = new SecretInformer(namespace);
                secretInformer.start();
                informers.add(secretInformer);
            }
        }
    }

    private void startClusterInformers() {
        logger.info("Initializing cluster informers ...");
        GlobalPluginConfiguration configuration = GlobalPluginConfiguration.get();
        if (configuration.isSyncBuildConfigsAndBuilds()) {
            BuildConfigClusterInformer buildConfigInformer = new BuildConfigClusterInformer(namespaces);
            informers.add(buildConfigInformer);
            buildConfigInformer.start();

            BuildClusterInformer buildInformer = new BuildClusterInformer(namespaces);
            informers.add(buildInformer);
            buildInformer.start();
        }
        if (configuration.isSyncConfigMaps()) {
            ConfigMapClusterInformer configMapInformer = new ConfigMapClusterInformer(namespaces);
            informers.add(configMapInformer);
            configMapInformer.start();
        }
        if (configuration.isSyncImageStreams()) {
            ImageStreamClusterInformer imageStreamInformer = new ImageStreamClusterInformer(namespaces);
            informers.add(imageStreamInformer);
            imageStreamInformer.start();
        }
        if (configuration.isSyncSecrets()) {
            SecretClusterInformer secretInformer = new SecretClusterInformer(namespaces);
            informers.add(secretInformer);
            secretInformer.start();
        }
    }

    public void stop() {
        logger.info("Stopping all informers ...");
        synchronized (this) {
            for (Lifecyclable informer : informers) {
                logger.info("Stopping informer: {}" + informer);
                informer.stop();
                logger.info("Stopped informer: {}" + informer);
            }
            informers.clear();
            logger.info("Stopped all informers");
        }
    }
}
