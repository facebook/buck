/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildRunEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.FileContentsProvider;
import com.facebook.buck.distributed.FileMaterializationStatsTracker;
import com.facebook.buck.distributed.build_slave.BuildSlaveService;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker.SlaveEvents;
import com.facebook.buck.distributed.build_slave.CapacityService;
import com.facebook.buck.distributed.build_slave.CoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.distributed.build_slave.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.build_slave.HealthCheckStatsTracker;
import com.facebook.buck.distributed.build_slave.MinionBuildProgressTracker;
import com.facebook.buck.distributed.build_slave.NoOpMinionBuildProgressTracker;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildSlaveEventBusListener;
import com.facebook.buck.event.listener.NoOpCoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.EventPostingRuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public class DistBuildRunCommand extends AbstractDistBuildCommand {
  private static final Logger LOG = Logger.get(DistBuildRunCommand.class);

  public static final String BUILD_STATE_FILE_ARG_NAME = "--build-state-file";
  public static final String BUILD_STATE_FILE_ARG_USAGE = "File containing the BuildStateJob data.";
  public static final int SHUTDOWN_TIMEOUT_MINUTES = 1;

  @Nullable
  @Option(name = BUILD_STATE_FILE_ARG_NAME, usage = BUILD_STATE_FILE_ARG_USAGE)
  private String buildStateFile;

  @Option(
      name = "--coordinator-port",
      usage = "Port of the remote build coordinator. (only used in MINION mode).")
  private int coordinatorPort = -1;

  @Option(
      name = "--coordinator-address",
      usage = "Address of the remote build coordinator. (only used in MINION mode).")
  private String coordinatorAddress = "localhost";

  @Nullable
  @Option(name = "--build-mode", usage = "The mode in which the distributed build is going to run.")
  private DistBuildMode distBuildMode = DistBuildMode.REMOTE_BUILD;

  @Nullable
  @Option(
      name = "--global-cache-dir",
      usage = "Full path to an existing directory that will contain a global cache across builds.")
  private Path globalCacheDir;

  private static final String RUN_ID_ARG_NAME = "--buildslave-run-id";

  @Nullable
  @Option(name = RUN_ID_ARG_NAME, usage = "Stampede RunId for this instance of BuildSlave.")
  private String buildSlaveRunId;

  @Nullable private DistBuildSlaveEventBusListener slaveEventListener;

  private CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher =
      new NoOpCoordinatorBuildRuleEventsPublisher();

  private MinionBuildProgressTracker minionBuildProgressTracker =
      new NoOpMinionBuildProgressTracker();

  private final FileMaterializationStatsTracker fileMaterializationStatsTracker =
      new FileMaterializationStatsTracker();

  private final HealthCheckStatsTracker healthCheckStatsTracker = new HealthCheckStatsTracker();

  private final BuildSlaveTimingStatsTracker timeStatsTracker = new BuildSlaveTimingStatsTracker();

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "runs a distributed build in the current machine (experimental)";
  }

  private static String getFullJobName(
      ImmutableMap<String, String> environment,
      String jobNameEnvironmentVariable,
      String taskIdEnvironmentVariable) {
    StringBuilder jobNameBuilder = new StringBuilder();

    ExecutionEnvironment executionEnvironment =
        new DefaultExecutionEnvironment(environment, System.getProperties());

    executionEnvironment
        .getenv(jobNameEnvironmentVariable)
        .ifPresent(val -> jobNameBuilder.append(val));
    executionEnvironment
        .getenv(taskIdEnvironmentVariable)
        .ifPresent(val -> jobNameBuilder.append("/").append(val));

    return jobNameBuilder.toString();
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    Optional<StampedeId> stampedeId = getStampedeIdOptional();
    if (stampedeId.isPresent()) {
      params.getBuckEventBus().post(new DistBuildRunEvent(stampedeId.get(), getBuildSlaveRunId()));
    }

    LOG.info("Starting DistBuildRunCommand.");

    timeStatsTracker.startTimer(SlaveEvents.TOTAL_RUNTIME);
    timeStatsTracker.startTimer(SlaveEvents.DIST_BUILD_PREPARATION_TIME);
    Console console = params.getConsole();

    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      if (slaveEventListener != null) {
        slaveEventListener.setDistBuildService(service);
      }

      try {
        timeStatsTracker.startTimer(SlaveEvents.DIST_BUILD_STATE_FETCH_TIME);
        Pair<BuildJobState, String> jobStateAndBuildName =
            getBuildJobStateAndBuildName(params.getCell().getFilesystem(), console, service);
        timeStatsTracker.stopTimer(SlaveEvents.DIST_BUILD_STATE_FETCH_TIME);

        BuildJobState jobState = jobStateAndBuildName.getFirst();
        String buildName = jobStateAndBuildName.getSecond();

        console
            .getStdOut()
            .println(
                String.format(
                    "BuildJob depends on a total of [%d] input deps.",
                    jobState.getFileHashesSize()));

        // Load up the remote build state from the client. Client-side .buckconfig is overlayed
        // with Stampede build slave .buckconfig.
        timeStatsTracker.startTimer(SlaveEvents.DIST_BUILD_STATE_LOADING_TIME);
        DistBuildState state =
            DistBuildState.load(
                params.getBuckConfig(),
                jobState,
                params.getCell(),
                params.getEnvironment(),
                params.getProcessExecutor(),
                params.getExecutableFinder(),
                params.getBuckModuleManager(),
                params.getPluginManager(),
                params.getProjectFilesystemFactory());
        timeStatsTracker.stopTimer(SlaveEvents.DIST_BUILD_STATE_LOADING_TIME);

        DistBuildConfig distBuildConfig = new DistBuildConfig(state.getRootCell().getBuckConfig());

        if (slaveEventListener != null) {
          if (distBuildConfig.getJobNameEnvironmentVariable().isPresent()
              && distBuildConfig.getTaskIdEnvironmentVariable().isPresent()) {
            slaveEventListener.setJobName(
                getFullJobName(
                    params.getEnvironment(),
                    distBuildConfig.getJobNameEnvironmentVariable().get(),
                    distBuildConfig.getTaskIdEnvironmentVariable().get()));
          }

          slaveEventListener.setBuildLabel(distBuildConfig.getBuildLabel());
          slaveEventListener.setMinionType(distBuildConfig.getMinionType().name());
        }

        ConcurrencyLimit concurrencyLimit =
            getConcurrencyLimit(state.getRootCell().getBuckConfig());

        try (CommandThreadManager pool =
                new CommandThreadManager(
                    getClass().getName(),
                    concurrencyLimit,
                    SHUTDOWN_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES);
            RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
                new EventPostingRuleKeyCacheScope<>(
                    params.getBuckEventBus(),
                    new TrackedRuleKeyCache<>(
                        new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker()))) {
          // Note that we cannot use the same pool of build threads for file materialization
          // because usually all build threads are waiting for files to be materialized, and
          // there is no thread left for the FileContentsProvider(s) to use.
          FileContentsProvider multiSourceFileContentsProvider =
              DistBuildFactory.createMultiSourceContentsProvider(
                  service,
                  distBuildConfig,
                  fileMaterializationStatsTracker,
                  params.getScheduledExecutor(),
                  Objects.requireNonNull(params.getExecutors().get(ExecutorPool.CPU)),
                  params.getProjectFilesystemFactory(),
                  getGlobalCacheDirOptional());

          try (BuildSlaveService buildSlaveService =
              DistBuildFactory.newBuildSlaveService(params)) {
            CapacityService capacityService =
                DistBuildFactory.newCapacityService(buildSlaveService, getBuildSlaveRunId());
            DistBuildSlaveExecutor distBuildExecutor =
                DistBuildFactory.createDistBuildExecutor(
                    state,
                    params,
                    pool.getWeightedListeningExecutorService(),
                    service,
                    Objects.requireNonNull(distBuildMode),
                    coordinatorPort,
                    coordinatorAddress,
                    stampedeId,
                    capacityService,
                    getBuildSlaveRunId(),
                    multiSourceFileContentsProvider,
                    healthCheckStatsTracker,
                    timeStatsTracker,
                    getCoordinatorBuildRuleEventsPublisher(),
                    getMinionBuildProgressTracker(),
                    ruleKeyCacheScope);

            distBuildExecutor.onBuildSlavePreparationCompleted(
                () -> timeStatsTracker.stopTimer(SlaveEvents.DIST_BUILD_PREPARATION_TIME));

            LOG.info("Starting to process build with DistBuildExecutor.");

            // All preparation work is done, so start building.
            ExitCode returnCode;
            try {
              returnCode = distBuildExecutor.buildAndReturnExitCode();
            } catch (Throwable ex) {
              LOG.error(ex, "buildAndReturnExitCode() failed");
              throw ex;
            }
            LOG.info(
                "%s returned with exit code: [%d].",
                distBuildExecutor.getClass().getName(), returnCode.getCode());
            multiSourceFileContentsProvider.close();
            LOG.info("Successfully shut down the source file provider.");
            timeStatsTracker.stopTimer(SlaveEvents.TOTAL_RUNTIME);

            if (slaveEventListener != null) {
              slaveEventListener.sendFinalServerUpdates(returnCode.getCode());
              slaveEventListener.publishServerSideBuildSlaveFinishedStatsEvent(
                  params.getBuckEventBus());
              LOG.info("Sent the final slave status and events.");
            }

            if (returnCode == ExitCode.SUCCESS) {
              console.printSuccess(
                  String.format(
                      "Successfully ran distributed build [%s] in [%d millis].",
                      buildName, timeStatsTracker.getElapsedTimeMs(SlaveEvents.TOTAL_RUNTIME)));
            } else {
              console.printErrorText(
                  "Failed distributed build [%s] in [%d millis].",
                  buildName, timeStatsTracker.getElapsedTimeMs(SlaveEvents.TOTAL_RUNTIME));
            }
            return returnCode;
          }
        }
      } catch (HumanReadableException e) {
        logBuildFailureEvent(e.getHumanReadableErrorMessage(), slaveEventListener);
        throw e;
      } catch (IOException | InterruptedException | RuntimeException e) {
        logBuildFailureEvent(e.getMessage(), slaveEventListener);
        throw e;
      }
    }
  }

  /** Logs a severe error message prefixed with {@code BUILD SLAVE FAILED} to the frontend. */
  private static void logBuildFailureEvent(
      String failureMessage, @Nullable DistBuildSlaveEventBusListener slaveEventListener) {
    if (slaveEventListener != null) {
      slaveEventListener.logEvent(
          ConsoleEvent.severe(String.format("BUILD SLAVE FAILED: %s", failureMessage)));
    }
  }

  public Pair<BuildJobState, String> getBuildJobStateAndBuildName(
      ProjectFilesystem filesystem, Console console, DistBuildService service) throws IOException {

    if (buildStateFile != null) {
      Path buildStateFilePath = Paths.get(buildStateFile);
      console
          .getStdOut()
          .println(
              String.format("Retrieving BuildJobState for from file [%s].", buildStateFilePath));
      return new Pair<>(
          BuildJobStateSerializer.deserialize(filesystem.newFileInputStream(buildStateFilePath)),
          String.format("LocalFile=[%s]", buildStateFile));
    } else {
      StampedeId stampedeId = getStampedeId();
      console
          .getStdOut()
          .println(String.format("Retrieving BuildJobState for build [%s].", stampedeId));
      return new Pair<>(
          service.fetchBuildJobState(stampedeId),
          String.format("DistBuild=[%s]", stampedeId.toString()));
    }
  }

  private Optional<Path> getGlobalCacheDirOptional() {
    if (globalCacheDir == null) {
      return Optional.empty();
    } else {
      if (!Files.isDirectory(globalCacheDir)) {
        try {
          Files.createDirectories(globalCacheDir);
        } catch (IOException exception) {
          throw new HumanReadableException(
              exception,
              "Directory passed for --global-cache-dir cannot be created. value=[%s]",
              globalCacheDir.toString());
        }
      }

      return Optional.of(globalCacheDir);
    }
  }

  private void checkArgs() {
    if (buildStateFile == null
        && (!getStampedeIdOptional().isPresent() || buildSlaveRunId == null)) {
      throw new HumanReadableException(
          String.format(
              "Options '%s' and '%s' are both required when '%s' is not provided.",
              STAMPEDE_ID_ARG_NAME, RUN_ID_ARG_NAME, BUILD_STATE_FILE_ARG_NAME));
    }
  }

  private BuildSlaveRunId getBuildSlaveRunId() {
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId(
        Objects.requireNonNull(
            this.buildSlaveRunId, "This should have been already made sure by checkArgs()."));
    return buildSlaveRunId;
  }

  private void initEventListener(ScheduledExecutorService scheduledExecutorService) {
    if (slaveEventListener == null) {
      checkArgs();

      slaveEventListener =
          new DistBuildSlaveEventBusListener(
              getStampedeId(),
              getBuildSlaveRunId(),
              Objects.requireNonNull(distBuildMode, "Dist build mode not set"),
              new DefaultClock(),
              timeStatsTracker,
              fileMaterializationStatsTracker,
              healthCheckStatsTracker,
              scheduledExecutorService);

      coordinatorBuildRuleEventsPublisher = slaveEventListener;
      minionBuildProgressTracker = slaveEventListener;
    }
  }

  private CoordinatorBuildRuleEventsPublisher getCoordinatorBuildRuleEventsPublisher() {
    return Objects.requireNonNull(coordinatorBuildRuleEventsPublisher);
  }

  private MinionBuildProgressTracker getMinionBuildProgressTracker() {
    return Objects.requireNonNull(minionBuildProgressTracker);
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    if (buildStateFile == null) {
      initEventListener(scheduledExecutorService);
      return ImmutableList.of(slaveEventListener);
    } else {
      return ImmutableList.of();
    }
  }
}
