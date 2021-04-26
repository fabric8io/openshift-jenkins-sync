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

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancelled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;
import static java.util.Collections.sort;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import com.cloudbees.plugins.credentials.CredentialsParameterDefinition;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.model.Action;
import hudson.model.BooleanParameterDefinition;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FileParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Queue;
import hudson.model.RunParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TopLevelItem;
import hudson.plugins.git.RevisionParameterAction;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildSpec;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.GitSourceRevision;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import io.fabric8.openshift.api.model.SourceRevision;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

/**
 */
public class JenkinsUtils {

	private static final Logger LOGGER = Logger.getLogger(JenkinsUtils.class.getName());
	private static final String PARAM_FROM_ENV_DESCRIPTION = "From OpenShift Build Environment Variable";

	public static Job getJob(String job) {
		TopLevelItem item = Jenkins.getActiveInstance().getItem(job);
		if (item instanceof Job) {
			return (Job) item;
		}
		return null;
	}

	public static String getRootUrl() {
		// TODO is there a better place to find this?
		String root = Jenkins.getActiveInstance().getRootUrl();
		if (root == null || root.length() == 0) {
			root = "http://localhost:8080/";
		}
		return root;
	}

	public static void verifyEnvVars(Map<String, ParameterDefinition> paramMap, WorkflowJob workflowJob, BuildConfig buildConfig) throws AbortException {
	    boolean rc;
        try {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    if (paramMap != null) {
                        String fullName = workflowJob.getFullName();
                        WorkflowJob job = null;
                        long now = System.currentTimeMillis();
                        boolean anyErrors = false;
                        do {
                            job = Jenkins.getActiveInstance()
                                    .getItemByFullName(fullName,
                                            WorkflowJob.class);
                            
                            if (job != null) {
                                if (anyErrors)
                                    LOGGER.info("finally found workflow job for " + job.getFullName());
                                break;
                            }
                            
                            anyErrors = true;
                            // this should not occur if an impersonate call has been made higher up
                            // the stack
                            LOGGER.warning("A run of workflow job " + workflowJob.getName() + " via fullname " + workflowJob.getFullName() + " unexpectantly not saved to disk.");
                            List<WorkflowJob> jobList = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
                            String jobNames = "";
                            for (WorkflowJob j : jobList) {
                                jobNames = jobNames + j.getFullName();
                            }
                            LOGGER.warning("The current list of full job names: " + jobNames);

                            try {
                                Thread.sleep(50l);
                            } catch (Throwable t) {
                                break;
                            }
                        } while ((System.currentTimeMillis() - 5000) > now);
                        if (job == null) {
                            // seen instances where if we throw exception immediately we lose our 
                            // most recent logger updates
                            try {
                                Thread.sleep(1000);
                            } catch (Throwable t) {
                                
                            }
                            throw new AbortException("workflow job " + workflowJob.getName() + " via fullname " + workflowJob.getFullName() + " could not be found ");
                        }
                        ParametersDefinitionProperty props = job.getProperty(ParametersDefinitionProperty.class);
                        List<String> names = props.getParameterDefinitionNames();
                        for (String name : names) {
                            if (!paramMap.containsKey(name)) {
                                throw new AbortException("A run of workflow job " + job.getName() + " was expecting parameter " + name + ", but it is not in the parameter list");
                            }
                        }
                    }
                    return null;
                }
            });
            
        } catch (Exception e) {
            if (e instanceof AbortException)
                throw (AbortException)e;
            throw new AbortException(e.getMessage());
        }
	}

	public static Map<String, ParameterDefinition> addJobParamForBuildEnvs(WorkflowJob job, JenkinsPipelineBuildStrategy strat,
			boolean replaceExisting) throws IOException {
		List<EnvVar> envs = strat.getEnv();
        Map<String, ParameterDefinition> paramMap = null;
		if (envs.size() > 0) {
			// build list of current env var names for possible deletion of env
			// vars currently stored
			// as job params
			List<String> envKeys = new ArrayList<String>();
			for (EnvVar env : envs) {
				envKeys.add(env.getName());
			}
			// get existing property defs, including any manually added from the
			// jenkins console independent of BC
			ParametersDefinitionProperty params = job.removeProperty(ParametersDefinitionProperty.class);
			paramMap = new HashMap<String, ParameterDefinition>();
			// store any existing parameters in map for easy key lookup
			if (params != null) {
				List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
				for (ParameterDefinition param : existingParamList) {
					// if a user supplied param, add
					if (param.getDescription() == null || !param.getDescription().equals(PARAM_FROM_ENV_DESCRIPTION))
						paramMap.put(param.getName(), param);
					else if (envKeys.contains(param.getName())) {
						// the env var still exists on the openshift side so
						// keep
						paramMap.put(param.getName(), param);
					}
				}
			}
			for (EnvVar env : envs) {
				if (replaceExisting) {
					StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), env.getValue() != null ? env.getValue() : "",
							PARAM_FROM_ENV_DESCRIPTION);
					paramMap.put(env.getName(), envVar);
				} else if (!paramMap.containsKey(env.getName())) {
					// if list from BC did not have this parameter, it was added
					// via `oc start-build -e` ... in this
					// case, we have chosen to make the default value an empty
					// string
					StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), "",
							PARAM_FROM_ENV_DESCRIPTION);
					paramMap.put(env.getName(), envVar);
				}
			}
			List<ParameterDefinition> newParamList = new ArrayList<ParameterDefinition>(paramMap.values());
			job.addProperty(new ParametersDefinitionProperty(newParamList));
		}
		// force save here ... seen some timing issues with concurrent job updates and run initiations
        InputStream jobStream = new StringInputStream(new XStream2().toXML(job));
		updateJob(job, jobStream, null, null);
		return paramMap;
	}

	public static List<Action> setJobRunParamsFromEnv(WorkflowJob job, JenkinsPipelineBuildStrategy strat,
			List<Action> buildActions) {
		List<EnvVar> envs = strat.getEnv();
		List<String> envKeys = new ArrayList<String>();
		List<ParameterValue> envVarList = new ArrayList<ParameterValue>();
		if (envs.size() > 0) {
			// build list of env var keys for compare with existing job params
			for (EnvVar env : envs) {
				envKeys.add(env.getName());
        // Convert null value to empty string.
        envVarList.add(new StringParameterValue(env.getName(), env.getValue() != null ? env.getValue() : ""));
			}
		}

		// add any existing job params that were not env vars, using their
		// default values
		ParametersDefinitionProperty params = job.getProperty(ParametersDefinitionProperty.class);
		if (params != null) {
			List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
			for (ParameterDefinition param : existingParamList) {
				if (!envKeys.contains(param.getName())) {
					String type = param.getType();
					switch (type) {
					case "BooleanParameterDefinition":
						BooleanParameterDefinition bpd = (BooleanParameterDefinition) param;
						envVarList.add(bpd.getDefaultParameterValue());
						break;
					case "ChoiceParameterDefinition":
						ChoiceParameterDefinition cpd = (ChoiceParameterDefinition) param;
						envVarList.add(cpd.getDefaultParameterValue());
						break;
					case "CredentialsParameterDefinition":
						CredentialsParameterDefinition crpd = (CredentialsParameterDefinition) param;
						envVarList.add(crpd.getDefaultParameterValue());
						break;
					case "FileParameterDefinition":
						FileParameterDefinition fpd = (FileParameterDefinition) param;
						envVarList.add(fpd.getDefaultParameterValue());
						break;
					// don't currently support since sync-plugin does not claim
					// subversion plugin as a direct dependency
					/*
					 * case "ListSubversionTagsParameterDefinition":
					 * ListSubversionTagsParameterDefinition lpd =
					 * (ListSubversionTagsParameterDefinition)param;
					 * envVarList.add(lpd.getDefaultParameterValue()); break;
					 */
					case "PasswordParameterDefinition":
						PasswordParameterDefinition ppd = (PasswordParameterDefinition) param;
						envVarList.add(ppd.getDefaultParameterValue());
						break;
					case "RunParameterDefinition":
						RunParameterDefinition rpd = (RunParameterDefinition) param;
						envVarList.add(rpd.getDefaultParameterValue());
						break;
					case "StringParameterDefinition":
						StringParameterDefinition spd = (StringParameterDefinition) param;
						envVarList.add(spd.getDefaultParameterValue());
						break;
					default:
						// used to have the following:
						// envVarList.add(new
						// StringParameterValue(param.getName(),
						// (param.getDefaultParameterValue() != null &&
						// param.getDefaultParameterValue().getValue() != null ?
						// param.getDefaultParameterValue().getValue().toString()
						// : "")));
						// but mvn verify complained
						ParameterValue pv = param.getDefaultParameterValue();
						if (pv != null) {
							Object val = pv.getValue();
							if (val != null) {
								envVarList.add(new StringParameterValue(param.getName(), val.toString()));
							}
						}
					}
				}
			}
		}

		if (envVarList.size() > 0)
			buildActions.add(new ParametersAction(envVarList));

		return buildActions;
	}

	public static List<Action> setJobRunParamsFromEnvAndUIParams(WorkflowJob job, JenkinsPipelineBuildStrategy strat,
			List<Action> buildActions, ParametersAction params) {
		List<EnvVar> envs = strat.getEnv();
		Map<String, ParameterValue> envVarMap = new LinkedHashMap<>(envs.size());
		if (envs.size() > 0) {
			// build map of env vars for compare with existing job params
			for (EnvVar env : envs) {
				// Convert null value to empty string.
				ParameterValue param = new StringParameterValue(env.getName(), env.getValue() != null ? env.getValue() : "");
				envVarMap.put(env.getName(), param);
			}
		}

		if (params != null) {
			for (ParameterValue userParam : params.getParameters()) {
				envVarMap.put(userParam.getName(), userParam);
			}
		}

		if (envVarMap.size() > 0)
			buildActions.add(new ParametersAction(new ArrayList<>(envVarMap.values())));

		return buildActions;
	}

	  /**
     * @param job   to trigger
     * @param build linked to it
     * @return true if "job" has been triggered
     * @throws IOException if job cannot be persisted
     */
    public static boolean triggerJob(WorkflowJob job, Build build) throws IOException {
        String buildConfigName = build.getStatus().getConfig().getName();
        if (isBlank(buildConfigName)) {
            return false;
        }

        ObjectMeta meta = build.getMetadata();
        String namespace = meta.getNamespace();
        BuildConfig buildConfig = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace)
                .withName(buildConfigName).get();
        if (buildConfig == null) {
            return false;
        }
        // sync on intern of name should guarantee sync on same actual obj
        synchronized (buildConfig.getMetadata().getUid().intern()) {
            if (isAlreadyTriggered(job, build)) {
                return false;
            }

            BuildConfigProjectProperty buildConfigProject = job.getProperty(BuildConfigProjectProperty.class);
            if (buildConfigProject == null || buildConfigProject.getBuildRunPolicy() == null) {
                LOGGER.warning(
                        "aborting trigger of build " + build + "because of missing bc project property or run policy");
                return false;
            }

            switch (buildConfigProject.getBuildRunPolicy()) {
            case SERIAL_LATEST_ONLY:
                cancelQueuedBuilds(job, buildConfigProject.getUid());
                if (job.isBuilding()) {
                    return false;
                }
                break;
            case SERIAL:
                if (job.isInQueue() || job.isBuilding()) {
                    return false;
                }
                break;
            default:
            }

            updateSourceCredentials(buildConfig);

            // We need to ensure that we do not remove existing Causes from a Run since
            // other plugins may rely on them.
            List<Cause> newCauses = new ArrayList<>();
            newCauses.add(new BuildCause(build, buildConfigProject.getUid()));
            CauseAction originalCauseAction = BuildToActionMapper.removeCauseAction(build.getMetadata().getName());
            if (originalCauseAction != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Adding existing causes...");
                    for (Cause c : originalCauseAction.getCauses()) {
                        LOGGER.fine("original cause: " + c.getShortDescription());
                    }
                }
                newCauses.addAll(originalCauseAction.getCauses());
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (Cause c : newCauses) {
                        LOGGER.fine("new cause: " + c.getShortDescription());
                    }
                }
            }

            List<Action> buildActions = new ArrayList<>();
            CauseAction bCauseAction = new CauseAction(newCauses);
            buildActions.add(bCauseAction);

            BuildSpec spec = build.getSpec();
            GitBuildSource gitBuildSource = spec.getSource().getGit();
            SourceRevision sourceRevision = spec.getRevision();

            if (gitBuildSource != null && sourceRevision != null) {
                GitSourceRevision gitSourceRevision = sourceRevision.getGit();
                if (gitSourceRevision != null) {
                    try {
                        URIish repoURL = new URIish(gitBuildSource.getUri());
                        buildActions.add(new RevisionParameterAction(gitSourceRevision.getCommit(), repoURL));
                    } catch (URISyntaxException e) {
                        LOGGER.log(SEVERE, "Failed to parse git repo URL" + gitBuildSource.getUri(), e);
                    }
                }
            }

            ParametersAction userProvidedParams = BuildToActionMapper
                    .removeParameterAction(build.getMetadata().getName());
            // grab envs from actual build in case user overrode default values
            // via `oc start-build -e`
            JenkinsPipelineBuildStrategy strat = spec.getStrategy().getJenkinsPipelineStrategy();
            // only add new param defs for build envs which are not in build
            // config envs
            Map<String, ParameterDefinition> paramMap = addJobParamForBuildEnvs(job, strat, false);
            verifyEnvVars(paramMap, job, buildConfig);
            if (userProvidedParams == null) {
                LOGGER.fine("setting all job run params since this was either started via oc, or started from the UI "
                        + "with no build parameters");
                // now add the actual param values stemming from openshift build
                // env vars for this specific job
                buildActions = setJobRunParamsFromEnv(job, strat, buildActions);
            } else {
                LOGGER.fine("setting job run params and since this is manually started from jenkins applying user "
                        + "provided parameters " + userProvidedParams + " along with any from bc's env vars");
                buildActions = setJobRunParamsFromEnvAndUIParams(job, strat, buildActions, userProvidedParams);
            }
            putJobWithBuildConfig(job, buildConfig);

            if (job.scheduleBuild2(0, buildActions.toArray(new Action[buildActions.size()])) != null) {
                updateOpenShiftBuildPhase(build, PENDING);
                // If builds are queued too quickly, Jenkins can add the cause
                // to the previous queued build so let's add a tiny
                // sleep.
                try {
                    Thread.sleep(50l);
                } catch (InterruptedException e) {
                    // Ignore
                }
                return true;
            }

            return false;
        }
    }
    
	private static boolean isAlreadyTriggered(WorkflowJob job, Build build) {
		return getRun(job, build) != null;
	}

	public static void cancelBuild(WorkflowJob job, Build build) {
		cancelBuild(job, build, false);
	}

	public static void cancelBuild(WorkflowJob job, Build build, boolean deleted) {
        if (!cancelQueuedBuild(job, build)) {
            cancelRunningBuild(job, build);
        }
        if (deleted) {
            return;
        }
        try {
            updateOpenShiftBuildPhase(build, CANCELLED);
        } catch (Exception e) {
            throw e;
        }
	}

	private static WorkflowRun getRun(WorkflowJob job, Build build) {
		if (build != null && build.getMetadata() != null) {
			return getRun(job, build.getMetadata().getUid(), 0);
		}
		return null;
	}

	private static WorkflowRun getRun(WorkflowJob job, String buildUid, int retry) {
	    try {
	        for (WorkflowRun run : job.getBuilds()) {
	            BuildCause cause = run.getCause(BuildCause.class);
	            if (cause != null && cause.getUid().equals(buildUid)) {
	                return run;
	            }
	        }
	    } catch (Throwable t) {
	        if (retry == 0) {
	            try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
	            return getRun(job, buildUid, 1);
	        }
	        LOGGER.log(Level.WARNING, "Jenkins unavailability accessing job run; have to assume it does not exist", t);
	    }
		return null;
	}

	public static void deleteRun(WorkflowRun run) {
			try {
			  LOGGER.info("Deleting run: " + run.toString());
				run.delete();
			} catch (IOException e) {
				LOGGER.warning("Unable to delete run " + run.toString() + ":" + e.getMessage());
			}
	}

  public static void deleteRun(WorkflowJob job, Build build) {
      WorkflowRun run = getRun(job, build);
      deleteRun(run);
  }

	private static boolean cancelRunningBuild(WorkflowJob job, Build build) {
		String buildUid = build.getMetadata().getUid();
		WorkflowRun run = getRun(job, buildUid, 0);
		if (run != null && run.isBuilding()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static boolean cancelNotYetStartedBuild(WorkflowJob job, Build build) {
		String buildUid = build.getMetadata().getUid();
		WorkflowRun run = getRun(job, buildUid, 0);
		if (run != null && run.hasntStartedYet()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static void cancelNotYetStartedBuilds(WorkflowJob job, String bcUid) {
		cancelQueuedBuilds(job, bcUid);
		for (WorkflowRun run : job.getBuilds()) {
			if (run != null && run.hasntStartedYet()) {
				BuildCause cause = run.getCause(BuildCause.class);
				if (cause != null && cause.getBuildConfigUid().equals(bcUid)) {
					terminateRun(run);
				}
			}
		}
	}

	private static void terminateRun(final WorkflowRun run) {
		ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
			@Override
			public Void call() throws RuntimeException {
				run.doTerm();
				Timer.get().schedule(new SafeTimerTask() {
					@Override
					public void doRun() {
						ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
							@Override
							public Void call() throws RuntimeException {
								run.doKill();
								return null;
							}
						});
					}
				}, 5, TimeUnit.SECONDS);
				return null;
			}
		});
	}

	@SuppressFBWarnings("SE_BAD_FIELD")
	public static boolean cancelQueuedBuild(WorkflowJob job, Build build) {
		String buildUid = build.getMetadata().getUid();
		final Queue buildQueue = Jenkins.getActiveInstance().getQueue();
		for (final Queue.Item item : buildQueue.getItems()) {
			for (Cause cause : item.getCauses()) {
				if (cause instanceof BuildCause && ((BuildCause) cause).getUid().equals(buildUid)) {
					return ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Boolean, RuntimeException>() {
						@Override
						public Boolean call() throws RuntimeException {
							buildQueue.cancel(item);
							return true;
						}
					});
				}
			}
		}
		return cancelNotYetStartedBuild(job, build);
	}

	public static void cancelQueuedBuilds(WorkflowJob job, String bcUid) {
		Queue buildQueue = Jenkins.getActiveInstance().getQueue();
		for (Queue.Item item : buildQueue.getItems()) {
			for (Cause cause : item.getCauses()) {
				if (cause instanceof BuildCause) {
					BuildCause buildCause = (BuildCause) cause;
					if (buildCause.getBuildConfigUid().equals(bcUid)) {
						Build build = new BuildBuilder().withNewMetadata().withNamespace(buildCause.getNamespace())
								.withName(buildCause.getName()).and().build();
						cancelQueuedBuild(job, build);
					}
				}
			}
		}
	}

	public static WorkflowJob getJobFromBuild(Build build) {
		String buildConfigName = build.getStatus().getConfig().getName();
		if (StringUtils.isEmpty(buildConfigName)) {
			return null;
		}

		WorkflowJob job = BuildConfigToJobMap.getJobFromBuildConfigNameNamespace(buildConfigName,
		        build.getMetadata().getNamespace());
		if (job != null) {
		    return job;
		}

		BuildConfig buildConfig = getAuthenticatedOpenShiftClient().buildConfigs()
				.inNamespace(build.getMetadata().getNamespace()).withName(buildConfigName).get();
		if (buildConfig == null) {
			return null;
		}
		return getJobFromBuildConfig(buildConfig);
	}

    public static void updateJob(WorkflowJob job, InputStream jobStream, String existingBuildRunPolicy, BuildConfigProjectProperty buildConfigProjectProperty) throws IOException {
        try {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    Source source = new StreamSource(jobStream);
                    job.updateByXml(source);
                    job.save();
                    if (existingBuildRunPolicy != null && buildConfigProjectProperty != null && !existingBuildRunPolicy.equals(buildConfigProjectProperty.getBuildRunPolicy())) {
                        maybeScheduleNext(job);
                    }
                    return null;
                }
            });
            
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

	public static void maybeScheduleNext(WorkflowJob job) {
		BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
		if (bcp == null) {
			return;
		}

		List<Build> builds = getAuthenticatedOpenShiftClient().builds().inNamespace(bcp.getNamespace())
				.withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW)
				.withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, bcp.getName()).list().getItems();
		handleBuildList(job, builds, bcp);
	}

	public static void handleBuildList(WorkflowJob job, List<Build> builds,
			BuildConfigProjectProperty buildConfigProjectProperty) {
		if (builds.isEmpty()) {
			return;
		}
		boolean isSerialLatestOnly = SERIAL_LATEST_ONLY.equals(buildConfigProjectProperty.getBuildRunPolicy());
		if (isSerialLatestOnly) {
			// Try to cancel any builds that haven't actually started, waiting
			// for executor perhaps.
			cancelNotYetStartedBuilds(job, buildConfigProjectProperty.getUid());
		}
		sort(builds, new Comparator<Build>() {
			@Override
			public int compare(Build b1, Build b2) {
				// Order so cancellations are first in list so we can stop
				// processing build list when build run policy is
				// SerialLatestOnly and job is currently building.
				Boolean b1Cancelled = b1.getStatus() != null && b1.getStatus().getCancelled() != null
						? b1.getStatus().getCancelled()
						: false;
				Boolean b2Cancelled = b2.getStatus() != null && b2.getStatus().getCancelled() != null
						? b2.getStatus().getCancelled()
						: false;
				// Inverse comparison as boolean comparison would put false
				// before true. Could have inverted both cancellation
				// states but this removes that step.
				int cancellationCompare = b2Cancelled.compareTo(b1Cancelled);
				if (cancellationCompare != 0) {
					return cancellationCompare;
				}

                if (b1.getMetadata().getAnnotations() == null
                        || b1.getMetadata().getAnnotations()
                                .get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
                    LOGGER.warning("cannot compare build "
                            + b1.getMetadata().getName()
                            + " from namespace "
                            + b1.getMetadata().getNamespace()
                            + ", has bad annotations: "
                            + b1.getMetadata().getAnnotations());
                    return 0;
                }
                if (b2.getMetadata().getAnnotations() == null
                        || b2.getMetadata().getAnnotations()
                                .get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER) == null) {
                    LOGGER.warning("cannot compare build "
                            + b2.getMetadata().getName()
                            + " from namespace "
                            + b2.getMetadata().getNamespace()
                            + ", has bad annotations: "
                            + b2.getMetadata().getAnnotations());
                    return 0;
                }
                int rc = 0;
                try {
                    rc = Long.compare(

                            Long.parseLong(b1
                                    .getMetadata()
                                    .getAnnotations()
                                    .get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
                            Long.parseLong(b2
                                    .getMetadata()
                                    .getAnnotations()
                                    .get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)));
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "handleBuildList", t);
                }
                return rc;
			}
		});
		boolean isSerial = SERIAL.equals(buildConfigProjectProperty.getBuildRunPolicy());
		boolean jobIsBuilding = job.isBuilding();
		for (int i = 0; i < builds.size(); i++) {
			Build b = builds.get(i);
			if (!OpenShiftUtils.isPipelineStrategyBuild(b))
				continue;
			// For SerialLatestOnly we should try to cancel all builds before
			// the latest one requested.
			if (isSerialLatestOnly) {
				// If the job is currently building, then let's return on the
				// first non-cancellation request so we do not try to
				// queue a new build.
				if (jobIsBuilding && !isCancelled(b.getStatus())) {
					return;
				}

				if (i < builds.size() - 1) {
					cancelQueuedBuild(job, b);
					updateOpenShiftBuildPhase(b, CANCELLED);
					continue;
				}
			}
			boolean buildAdded = false;
			try {
				buildAdded =  BuildManager.addEventToJenkinsJobRun(b);
			} catch (IOException e) {
				ObjectMeta meta = b.getMetadata();
				LOGGER.log(WARNING, "Failed to add new build " + meta.getNamespace() + "/" + meta.getName(), e);
			}
			// If it's a serial build then we only need to schedule the first
			// build request.
			if (isSerial && buildAdded) {
				return;
			}
		}
	}

	public static String getFullJobName(WorkflowJob job) {
		return job.getRelativeNameFrom(Jenkins.getInstance());
	}

	public static String getBuildConfigName(WorkflowJob job) {
		String name = getFullJobName(job);
		GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
		String[] paths = name.split("/");
		if (paths.length > 1) {
			String orgName = paths[0];
			if (StringUtils.isNotBlank(orgName)) {
				if (config != null) {
					String skipOrganizationPrefix = config.getSkipOrganizationPrefix();
					if (StringUtils.isEmpty(skipOrganizationPrefix)) {
						config.setSkipOrganizationPrefix(orgName);
						skipOrganizationPrefix = config.getSkipOrganizationPrefix();
					}

					// if the default organization lets strip the organization name from the prefix
					int prefixLength = orgName.length() + 1;
					if (orgName.equals(skipOrganizationPrefix) && name.length() > prefixLength) {
						name = name.substring(prefixLength);
					}
				}
			}
		}

		// lets avoid the .master postfixes as we treat master as the default branch
		// name
		String masterSuffix = "/master";
		if (config != null) {
			String skipBranchSuffix = config.getSkipBranchSuffix();
			if (StringUtils.isEmpty(skipBranchSuffix)) {
				config.setSkipBranchSuffix("master");
				skipBranchSuffix = config.getSkipBranchSuffix();
			}
			masterSuffix = "/" + skipBranchSuffix;
		}
		if (name.endsWith(masterSuffix) && name.length() > masterSuffix.length()) {
			name = name.substring(0, name.length() - masterSuffix.length());
		}
		return name;
	}

	public static KubernetesCloud getKubernetesCloud() {
		// pedantic mvn:findbugs
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null)
			return null;
		Cloud openShiftCloud = jenkins.getCloud("openshift");
		if (openShiftCloud instanceof KubernetesCloud) {
			return (KubernetesCloud) openShiftCloud;
		}

		return null;
	}


}
