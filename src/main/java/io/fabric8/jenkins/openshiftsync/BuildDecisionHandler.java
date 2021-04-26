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

import hudson.Extension;
import hudson.model.Action;
import hudson.model.ParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildRequestBuilder;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildSyncRunListener.joinPaths;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getJenkinsURL;

@Extension
public class BuildDecisionHandler extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER = Logger
            .getLogger(BuildDecisionHandler.class.getName());

    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        if (p instanceof WorkflowJob && !isOpenShiftBuildCause(actions)) {
            WorkflowJob wj = (WorkflowJob) p;
            BuildConfigProjectProperty buildConfigProjectProperty = wj
                    .getProperty(BuildConfigProjectProperty.class);
            if (buildConfigProjectProperty != null
                    && StringUtils.isNotBlank(buildConfigProjectProperty
                            .getNamespace())
                    && StringUtils.isNotBlank(buildConfigProjectProperty
                            .getName())) {

                String namespace = buildConfigProjectProperty.getNamespace();
                String jobURL = joinPaths(
                        getJenkinsURL(getAuthenticatedOpenShiftClient(),
                                namespace), wj.getUrl());

                Build ret = getAuthenticatedOpenShiftClient()
                        .buildConfigs()
                        .inNamespace(namespace)
                        .withName(buildConfigProjectProperty.getName())
                        .instantiate(
                                new BuildRequestBuilder()
                                        .withNewMetadata()
                                        .withName(
                                                buildConfigProjectProperty
                                                        .getName())
                                        .and()
                                        .addNewTriggeredBy()
                                        .withMessage(
                                                "Triggered by Jenkins job at "
                                                        + jobURL).and().build());

                ParametersAction params = dumpParams(actions);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("ParametersAction: " + params);
                }
                if (params != null && ret != null)
                    BuildToActionMapper.addParameterAction(ret.getMetadata()
                            .getName(), params);

                CauseAction cause = dumpCause(actions);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("get CauseAction: " + cause.getDisplayName());
                    for (Cause c : cause.getCauses()) {
                        LOGGER.fine("Cause: " + c.getShortDescription());
                    }
                }
                if (cause != null && ret != null)
                    BuildToActionMapper.addCauseAction(ret.getMetadata()
                            .getName(), cause);

                return false;
            }
        }

        return true;
    }

    private static boolean isOpenShiftBuildCause(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                for (Cause cause : causeAction.getCauses()) {
                    if (cause instanceof BuildCause) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static CauseAction dumpCause(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                if (LOGGER.isLoggable(Level.FINE))
                    for (Cause cause : causeAction.getCauses()) {
                        LOGGER.fine("cause: " + cause.getShortDescription());
                    }
                return causeAction;
            }
        }
        return null;
    }

    private static ParametersAction dumpParams(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof ParametersAction) {
                ParametersAction paramAction = (ParametersAction) action;
                if (LOGGER.isLoggable(Level.FINE))
                    for (ParameterValue param : paramAction.getAllParameters()) {
                        LOGGER.fine("param name " + param.getName()
                                + " param value " + param.getValue());
                    }
                return paramAction;
            }
        }
        return null;
    }

}
