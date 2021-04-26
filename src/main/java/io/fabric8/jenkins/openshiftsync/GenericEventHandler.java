package io.fabric8.jenkins.openshiftsync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

public class GenericEventHandler<T extends HasMetadata> implements ResourceEventHandler<T> {
    private Logger logger = LoggerFactory.getLogger(this.getClass().getName());

    public void onAdd(T obj) {
        String className = obj.getClass().getSimpleName();
        final String name = obj.getMetadata().getName();
        logger.info("{}/{} added", className, name);
    }

    public void onUpdate(T oldObj, T newObj) {
        String className = oldObj.getClass().getSimpleName();
        final String name = oldObj.getMetadata().getName();
        logger.info("{}/{} updated", className, name);
    }

    public void onDelete(T obj, boolean deletedFinalStateUnknown) {
        String className = obj.getClass().getSimpleName();
        final String name = obj.getMetadata().getName();
        logger.info("{}/{} deleted", className, name);
    }

}