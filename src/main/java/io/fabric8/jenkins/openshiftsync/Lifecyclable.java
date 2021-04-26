package io.fabric8.jenkins.openshiftsync;

public interface Lifecyclable {
    public void stop();
    public void start();

}
