package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;

import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.fabric8.openshift.api.model.Build;

public class BuildComparator implements Comparator<Build> {
    private static final Logger LOGGER = Logger.getLogger(BuildInformer.class.getName());

    @Override
    public int compare(Build b1, Build b2) {
        if (b1.getMetadata().getAnnotations() == null
                || b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
            LOGGER.warning("cannot compare build " + b1.getMetadata().getName() + " from namespace "
                    + b1.getMetadata().getNamespace() + ", has bad annotations: " + b1.getMetadata().getAnnotations());
            return 0;
        }
        if (b2.getMetadata().getAnnotations() == null
                || b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
            LOGGER.warning("cannot compare build " + b2.getMetadata().getName() + " from namespace "
                    + b2.getMetadata().getNamespace() + ", has bad annotations: " + b2.getMetadata().getAnnotations());
            return 0;
        }
        int rc = 0;
        try {
            rc = Long.compare(

                    Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
                    Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)));
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "onInitialBuilds", t);
        }
        return rc;
    }
}
