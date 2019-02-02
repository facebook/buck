/*
 * Copyright 2018-present Facebook, Inc.
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

import static com.facebook.buck.cli.BuildCommand.BUCK_BINARY_STRING_ARG;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_GRAPH_CONSTRUCTION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_LOCAL_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_BUILD_ANALYSIS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_DISTRIBUTED_BUILD_LOCAL_STEPS;
import static com.facebook.buck.util.concurrent.MostExecutors.newMultiThreadExecutor;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.cli.BuildCommand.ActionGraphCreationException;
import com.facebook.buck.cli.BuildCommand.BuildRunResult;
import com.facebook.buck.cli.BuildCommand.GraphsAndBuildTargets;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutor;
import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizer;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.AnalysisResults;
import com.facebook.buck.distributed.BuckVersionUtil;
import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildClientStatsEvent;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildPostBuildAnalysis;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistLocalBuildMode;
import com.facebook.buck.distributed.DistributedExitCode;
import com.facebook.buck.distributed.RuleKeyNameAndType;
import com.facebook.buck.distributed.build_client.DistBuildControllerArgs;
import com.facebook.buck.distributed.build_client.DistBuildControllerInvocationArgs;
import com.facebook.buck.distributed.build_client.DistBuildSuperConsoleEvent;
import com.facebook.buck.distributed.build_client.LogStateTracker;
import com.facebook.buck.distributed.build_client.StampedeBuildClient;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.RemoteCommand;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildClientEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

public class DistBuildCommandDelegate {

  private static final Logger LOG = Logger.get(DistBuildCommandDelegate.class);

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";

  private static final int STAMPEDE_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 100;

  private final DistBuildClientEventListener distBuildClientEventListener =
      new DistBuildClientEventListener();

  @Nullable private final String distributedBuildStateFile;
  @Nullable private final String buckBinary;
  private final SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator;
  private final boolean keepGoing;

  private boolean autoDistBuild = false;
  private Optional<String> autoDistBuildMessage = Optional.empty();

  public DistBuildCommandDelegate(
      @Nullable String distributedBuildStateFile,
      @Nullable String buckBinary,
      SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator,
      boolean keepGoing) {
    this.distributedBuildStateFile = distributedBuildStateFile;
    this.buckBinary = buckBinary;
    this.localRuleKeyCalculator = localRuleKeyCalculator;
    this.keepGoing = keepGoing;
  }

  public BuildRunResult executeBuildAndProcessResult(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      BuildCommand buildCommand)
      throws IOException, InterruptedException, ActionGraphCreationException {
    DistBuildConfig distBuildConfig = new DistBuildConfig(params.getBuckConfig());
    ClientStatsTracker distBuildClientStatsTracker =
        new ClientStatsTracker(
            distBuildConfig.getBuildLabel(), distBuildConfig.getMinionType().toString());

    distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
    distBuildClientStatsTracker.startTimer(LOCAL_GRAPH_CONSTRUCTION);
    GraphsAndBuildTargets graphsAndBuildTargets =
        buildCommand.createGraphsAndTargets(
            params, commandThreadManager.getListeningExecutorService(), Optional.empty());
    distBuildClientStatsTracker.stopTimer(LOCAL_GRAPH_CONSTRUCTION);

    ExitCode exitCode;
    try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
        buildCommand.getDefaultRuleKeyCacheScope(
            params, graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder())) {
      try {
        exitCode =
            executeDistBuild(
                params,
                buildCommand,
                distBuildConfig,
                graphsAndBuildTargets,
                commandThreadManager.getWeightedListeningExecutorService(),
                params.getCell().getFilesystem(),
                params.getFileHashCache(),
                distBuildClientStatsTracker,
                ruleKeyCacheScope);
      } catch (Throwable ex) {
        String stackTrace = Throwables.getStackTraceAsString(ex);
        distBuildClientStatsTracker.setBuckClientErrorMessage(ex + "\n" + stackTrace);
        distBuildClientStatsTracker.setBuckClientError(true);

        throw ex;
      } finally {
        if (distributedBuildStateFile == null) {
          params
              .getBuckEventBus()
              .post(new DistBuildClientStatsEvent(distBuildClientStatsTracker.generateStats()));
        }
      }
      if (exitCode == ExitCode.SUCCESS) {
        exitCode =
            buildCommand.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
      }
    }
    return ImmutableBuildRunResult.of(exitCode, graphsAndBuildTargets.getBuildTargets());
  }

  private ExitCode executeDistBuild(
      CommandRunnerParams params,
      BuildCommand buildCommand,
      DistBuildConfig distBuildConfig,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executorService,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      ClientStatsTracker distBuildClientStats,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException, InterruptedException {
    Objects.requireNonNull(distBuildClientEventListener);

    Preconditions.checkArgument(
        !distBuildConfig.getPerformRuleKeyConsistencyCheck()
            || distBuildConfig.getLogMaterializationEnabled(),
        "Log materialization must be enabled to perform rule key consistency check.");

    if (distributedBuildStateFile == null
        && distBuildConfig.getBuildMode().equals(BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR)
        && !distBuildConfig.getMinionQueue().isPresent()) {
      throw new HumanReadableException(
          "Stampede Minion Queue name must be specified to use Local Coordinator Mode.");
    }

    BuildEvent.DistBuildStarted started = BuildEvent.distBuildStarted();
    params.getBuckEventBus().post(started);
    if (!autoDistBuild) {
      // Enable Stampede console now, but only if it's an explicit stampede build.
      params.getBuckEventBus().post(new DistBuildSuperConsoleEvent());
    }

    LOG.info("Starting async file hash computation and job state serialization.");
    RemoteCommand remoteCommand =
        distBuildConfig.getLocalBuildMode() == DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK
            ? RemoteCommand.RULE_KEY_DIVERGENCE_CHECK
            : RemoteCommand.BUILD;
    AsyncJobStateAndCells stateAndCells =
        AsyncJobStateFactory.computeDistBuildState(
            params,
            graphsAndBuildTargets,
            executorService,
            Optional.of(distBuildClientStats),
            remoteCommand);
    ListenableFuture<BuildJobState> asyncJobState = stateAndCells.getAsyncJobState();
    DistBuildCellIndexer distBuildCellIndexer = stateAndCells.getDistBuildCellIndexer();

    if (distributedBuildStateFile != null) {
      BuildJobState jobState;
      try {
        jobState = asyncJobState.get();
      } catch (ExecutionException e) {
        throw new RuntimeException("Failed to compute DistBuildState.", e);
      }

      // Read all files inline if we're dumping state to a file.
      for (BuildJobStateFileHashes cell : jobState.getFileHashes()) {
        ProjectFilesystem cellFilesystem =
            Objects.requireNonNull(
                distBuildCellIndexer.getLocalFilesystemsByCellIndex().get(cell.getCellIndex()));
        for (BuildJobStateFileHashEntry entry : cell.getEntries()) {
          cellFilesystem
              .readFileIfItExists(cellFilesystem.resolve(entry.getPath().getPath()))
              .ifPresent(contents -> entry.setContents(contents.getBytes()));
        }
      }

      Path stateDumpPath = Paths.get(distributedBuildStateFile);
      BuildJobStateSerializer.serialize(jobState, filesystem.newFileOutputStream(stateDumpPath));
      return ExitCode.SUCCESS;
    }

    BuckVersion buckVersion = getBuckVersion();
    Preconditions.checkArgument(params.getInvocationInfo().isPresent());

    distBuildClientStats.setIsLocalFallbackBuildEnabled(
        distBuildConfig.isSlowLocalBuildFallbackModeEnabled());

    try (DistBuildService distBuildService = DistBuildFactory.newDistBuildService(params);
        RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer =
            new RemoteBuildRuleSynchronizer(
                params.getClock(),
                params.getScheduledExecutor(),
                distBuildConfig.getCacheSynchronizationFirstBackoffMillis(),
                distBuildConfig.getCacheSynchronizationMaxTotalBackoffMillis())) {
      ListeningExecutorService stampedeControllerExecutor =
          createStampedeControllerExecutorService(distBuildConfig.getControllerMaxThreadCount());

      ListeningExecutorService stampedeLocalBuildExecutor =
          createStampedeLocalBuildExecutorService();

      LogStateTracker distBuildLogStateTracker =
          DistBuildFactory.newDistBuildLogStateTracker(
              params.getInvocationInfo().get().getLogDirectoryPath(), filesystem, distBuildService);

      DistBuildControllerArgs.Builder distBuildControllerArgsBuilder =
          DistBuildControllerArgs.builder()
              .setBuilderExecutorArgs(params.createBuilderArgs())
              .setBuckEventBus(params.getBuckEventBus())
              .setDistBuildStartedEvent(started)
              .setTopLevelTargets(graphsAndBuildTargets.getBuildTargets())
              .setBuildGraphs(graphsAndBuildTargets.getGraphs())
              .setCachingBuildEngineDelegate(
                  Optional.of(new LocalCachingBuildEngineDelegate(params.getFileHashCache())))
              .setAsyncJobState(asyncJobState)
              .setDistBuildCellIndexer(distBuildCellIndexer)
              .setDistBuildService(distBuildService)
              .setDistBuildLogStateTracker(distBuildLogStateTracker)
              .setBuckVersion(buckVersion)
              .setDistBuildClientStats(distBuildClientStats)
              .setScheduler(params.getScheduledExecutor())
              .setMaxTimeoutWaitingForLogsMillis(
                  distBuildConfig.getMaxWaitForRemoteLogsToBeAvailableMillis())
              .setLogMaterializationEnabled(distBuildConfig.getLogMaterializationEnabled())
              .setBuildLabel(distBuildConfig.getBuildLabel());

      LocalBuildExecutorInvoker localBuildExecutorInvoker =
          new LocalBuildExecutorInvoker() {
            @Override
            public void initLocalBuild(
                boolean isDownloadHeavyBuild,
                RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {
              DistBuildCommandDelegate.this.initLocalBuild(
                  params,
                  buildCommand,
                  graphsAndBuildTargets,
                  executorService,
                  Optional.empty(),
                  remoteBuildRuleCompletionWaiter,
                  isDownloadHeavyBuild,
                  ruleKeyCacheScope);
            }

            @Override
            public ExitCode executeLocalBuild(
                boolean isDownloadHeavyBuild,
                RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
                CountDownLatch initializeBuildLatch,
                AtomicReference<Build> buildReference)
                throws Exception {
              return buildCommand.executeLocalBuild(
                  params,
                  graphsAndBuildTargets,
                  executorService,
                  Optional.empty(),
                  remoteBuildRuleCompletionWaiter,
                  isDownloadHeavyBuild,
                  Optional.of(initializeBuildLatch),
                  ruleKeyCacheScope,
                  buildReference);
            }
          };

      DistBuildControllerInvocationArgs distBuildControllerInvocationArgs =
          DistBuildControllerInvocationArgs.builder()
              .setExecutorService(stampedeControllerExecutor)
              .setProjectFilesystem(filesystem)
              .setFileHashCache(fileHashCache)
              .setInvocationInfo(params.getInvocationInfo().get())
              .setBuildMode(distBuildConfig.getBuildMode())
              .setDistLocalBuildMode(distBuildConfig.getLocalBuildMode())
              .setMinionRequirements(distBuildConfig.getMinionRequirements())
              .setRepository(distBuildConfig.getRepository())
              .setTenantId(distBuildConfig.getTenantId())
              .setRuleKeyCalculatorFuture(localRuleKeyCalculator)
              .build();

      // TODO(alisdair): ensure minion build status recorded even if local build finishes first.
      boolean waitForDistBuildThreadToFinishGracefully =
          distBuildConfig.getLogMaterializationEnabled();
      long distributedBuildThreadKillTimeoutSeconds =
          distBuildConfig.getDistributedBuildThreadKillTimeoutSeconds();

      StampedeBuildClient stampedeBuildClient =
          new StampedeBuildClient(
              params.getBuckEventBus(),
              stampedeLocalBuildExecutor,
              stampedeControllerExecutor,
              distBuildService,
              started,
              localBuildExecutorInvoker,
              distBuildControllerArgsBuilder,
              distBuildControllerInvocationArgs,
              distBuildClientStats,
              waitForDistBuildThreadToFinishGracefully,
              distributedBuildThreadKillTimeoutSeconds,
              autoDistBuildMessage,
              remoteBuildRuleSynchronizer);

      distBuildClientStats.startTimer(PERFORM_LOCAL_BUILD);

      // Perform either a single phase build that waits for all remote artifacts before proceeding,
      // or a two stage build where local build first races against remote, and depending on
      // progress either completes first or falls back to build that waits for remote artifacts.
      Optional<ExitCode> localExitCodeOption =
          stampedeBuildClient.build(
              distBuildConfig.getLocalBuildMode(),
              distBuildConfig.isSlowLocalBuildFallbackModeEnabled());

      ExitCode localExitCode = localExitCodeOption.orElse(ExitCode.FATAL_GENERIC);

      // All local/distributed build steps are now finished.
      StampedeId stampedeId = stampedeBuildClient.getStampedeId();
      DistributedExitCode distributedBuildExitCode = stampedeBuildClient.getDistBuildExitCode();
      distBuildClientStats.setStampedeId(stampedeId.getId());
      distBuildClientStats.setDistributedBuildExitCode(distributedBuildExitCode.getCode());

      // Set local build stats
      distBuildClientStats.setPerformedLocalBuild(true);
      distBuildClientStats.stopTimer(PERFORM_LOCAL_BUILD);

      distBuildClientStats.setLocalBuildExitCode(localExitCode.getCode());
      // If local build finished before hashing was complete, it's important to cancel
      // related Futures to avoid this operation blocking forever.
      asyncJobState.cancel(true);

      // stampedeControllerExecutor is now redundant. Kill it as soon as possible.
      killExecutor(
          stampedeControllerExecutor,
          ("Stampede controller executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      killExecutor(
          stampedeLocalBuildExecutor,
          ("Stampede local build executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      ExitCode finalExitCode;
      DistLocalBuildMode distLocalBuildMode =
          distBuildControllerInvocationArgs.getDistLocalBuildMode();
      if (distLocalBuildMode.equals(DistLocalBuildMode.FIRE_AND_FORGET)
          || distLocalBuildMode.equals(DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK)) {
        finalExitCode = localExitCode;
      } else {
        finalExitCode =
            performPostBuild(
                params,
                distBuildConfig,
                filesystem,
                distBuildClientStats,
                stampedeId,
                distributedBuildExitCode,
                localExitCode,
                distBuildService,
                distBuildLogStateTracker);
      }

      // If no local fallback, and there was a stampede infrastructure failure,
      // then return corresponding exit code
      if (finalExitCode != ExitCode.SUCCESS
          && !distBuildConfig.isSlowLocalBuildFallbackModeEnabled()
          && DistributedExitCode.wasStampedeInfraFailure(distributedBuildExitCode)) {
        finalExitCode = ExitCode.STAMPEDE_INFRA_ERROR;
      }

      return finalExitCode;
    }
  }

  private ListeningExecutorService createStampedeControllerExecutorService(int maxThreads) {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory(
            "StampedeController", GlobalStateManager.singleton().getThreadToCommandRegister());
    return MoreExecutors.listeningDecorator(
        newMultiThreadExecutor(stampedeCommandThreadFactory, maxThreads));
  }

  private ListeningExecutorService createStampedeLocalBuildExecutorService() {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory(
            "StampedeLocalBuild", GlobalStateManager.singleton().getThreadToCommandRegister());
    return MoreExecutors.listeningDecorator(
        MostExecutors.newSingleThreadExecutor(stampedeCommandThreadFactory));
  }

  private void killExecutor(
      ListeningExecutorService stampedeControllerExecutor, String failureWarning)
      throws InterruptedException {
    stampedeControllerExecutor.shutdown();
    if (!stampedeControllerExecutor.awaitTermination(
        STAMPEDE_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      LOG.warn(failureWarning);
      stampedeControllerExecutor.shutdownNow();
    }
  }

  private boolean performStampedePostBuildAnalysisAndRuleKeyConsistencyChecks(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ProjectFilesystem filesystem,
      ClientStatsTracker distBuildClientStats,
      StampedeId stampedeId,
      DistributedExitCode distributedBuildExitCode,
      ExitCode localBuildExitCode,
      LogStateTracker distBuildLogStateTracker)
      throws IOException {
    // If we are pulling down remote logs, and the distributed build finished successfully,
    // then perform analysis
    if (distBuildConfig.getLogMaterializationEnabled()
        && distributedBuildExitCode == DistributedExitCode.SUCCESSFUL
        && localBuildExitCode == ExitCode.SUCCESS) {
      distBuildClientStats.startTimer(POST_BUILD_ANALYSIS);
      DistBuildPostBuildAnalysis postBuildAnalysis =
          new DistBuildPostBuildAnalysis(
              params.getInvocationInfo().get().getBuildId(),
              stampedeId,
              filesystem.resolve(params.getInvocationInfo().get().getLogDirectoryPath()),
              distBuildLogStateTracker.getBuildSlaveLogsMaterializer().getMaterializedRunIds(),
              DistBuildCommand.class.getSimpleName().toLowerCase());

      LOG.info("Created DistBuildPostBuildAnalysis");
      AnalysisResults results = postBuildAnalysis.runAnalysis();
      Path analysisSummaryFile = postBuildAnalysis.dumpResultsToLogFile(results);

      distBuildClientStats.stopTimer(POST_BUILD_ANALYSIS);

      LOG.info(String.format("Dumped DistBuildPostBuildAnalysis to [%s]", analysisSummaryFile));
      Path relativePathToSummaryFile = filesystem.getRootPath().relativize(analysisSummaryFile);
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.warning(
                  "Details of distributed build analysis: %s",
                  relativePathToSummaryFile.toString()));

      LOG.info(
          "Number of mismatching default rule keys: " + results.numMismatchingDefaultRuleKeys());
      if (distBuildConfig.getPerformRuleKeyConsistencyCheck()
          && results.numMismatchingDefaultRuleKeys() > 0) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    "*** [%d] default rule keys mismatched between client and server. *** \nMismatching rule keys:",
                    results.numMismatchingDefaultRuleKeys()));

        for (RuleKeyNameAndType ruleKeyNameAndType :
            postBuildAnalysis.getMismatchingDefaultRuleKeys(results)) {
          params
              .getBuckEventBus()
              .post(
                  ConsoleEvent.severe(
                      "MISMATCHING RULE: %s [%s]",
                      ruleKeyNameAndType.getRuleName(), ruleKeyNameAndType.getRuleType()));
        }

        return false; // Rule keys were not consistent
      }
    }

    return true; // Rule keys were consistent, or test was skipped.
  }

  private ExitCode performPostBuild(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ProjectFilesystem filesystem,
      ClientStatsTracker distBuildClientStats,
      StampedeId stampedeId,
      DistributedExitCode distributedBuildExitCode,
      ExitCode localExitCode,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker)
      throws IOException {
    // Publish details about all default rule keys that were cache misses.
    // A non-zero value suggests a problem that needs investigating.
    if (distBuildConfig.isCacheMissAnalysisEnabled()) {
      performCacheMissAnalysis(params, distBuildConfig, distBuildService);
    }

    boolean ruleKeyConsistencyChecksPassedOrSkipped =
        performStampedePostBuildAnalysisAndRuleKeyConsistencyChecks(
            params,
            distBuildConfig,
            filesystem,
            distBuildClientStats,
            stampedeId,
            distributedBuildExitCode,
            localExitCode,
            distBuildLogStateTracker);

    ExitCode finalExitCode = localExitCode;
    if (!ruleKeyConsistencyChecksPassedOrSkipped) {
      finalExitCode = ExitCode.BUILD_ERROR;
    }

    // Post distributed build phase starts POST_DISTRIBUTED_BUILD_LOCAL_STEPS counter internally.
    if (distributedBuildExitCode == DistributedExitCode.SUCCESSFUL) {
      distBuildClientStats.stopTimer(POST_DISTRIBUTED_BUILD_LOCAL_STEPS);
    }

    return finalExitCode;
  }

  private void performCacheMissAnalysis(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService) {
    try {
      Set<String> cacheMissRequestKeys =
          distBuildClientEventListener.getDefaultCacheMissRequestKeys();
      ArtifactCacheBuckConfig artifactCacheBuckConfig =
          ArtifactCacheBuckConfig.of(distBuildConfig.getBuckConfig());

      LOG.info(
          String.format(
              "Fetching rule key logs for [%d] cache misses", cacheMissRequestKeys.size()));
      if (cacheMissRequestKeys.size() > 0) {
        // TODO(alisdair): requests should be batched for high key counts.
        List<RuleKeyLogEntry> ruleKeyLogs =
            distBuildService.fetchRuleKeyLogs(
                cacheMissRequestKeys,
                artifactCacheBuckConfig.getRepository(),
                artifactCacheBuckConfig.getScheduleType(),
                true /* distributedBuildModeEnabled */);
        params
            .getBuckEventBus()
            .post(distBuildClientEventListener.createDistBuildClientCacheResultsEvent(ruleKeyLogs));
      }
      LOG.info(
          String.format(
              "Fetched rule key logs for [%d] cache misses", cacheMissRequestKeys.size()));

    } catch (Exception ex) {
      LOG.error("Failed to publish distributed build client cache request event", ex);
    }
  }

  /** Initializes localRuleKeyCalculator (for use in rule key divergence checker) */
  protected void initLocalBuild(
      CommandRunnerParams params,
      BuildCommand buildCommand,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      boolean isDownloadHeavyBuild,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope) {
    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    LocalBuildExecutor builder =
        new LocalBuildExecutor(
            params.createBuilderArgs(),
            buildCommand.getExecutionContext(),
            actionGraphAndBuilder,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            keepGoing,
            true,
            isDownloadHeavyBuild,
            ruleKeyCacheScope,
            buildCommand.getBuildEngineMode(),
            ruleKeyLogger,
            remoteBuildRuleCompletionWaiter,
            params.getMetadataProvider(),
            params.getUnconfiguredBuildTargetFactory(),
            buildCommand.getTargetConfiguration());
    localRuleKeyCalculator.set(builder.getCachingBuildEngine().getRuleKeyCalculator());
    builder.shutdown();
  }

  public Iterable<BuckEventListener> getEventListeners() {
    return Collections.singletonList(distBuildClientEventListener);
  }

  public void tryConvertingToStampede(DistBuildConfig config) {
    autoDistBuild = true;
    autoDistBuildMessage = config.getAutoDistributedBuildMessage();
  }

  private BuckVersion getBuckVersion() throws IOException {
    if (buckBinary == null) {
      String gitHash = System.getProperty(BUCK_GIT_COMMIT_KEY, null);
      if (gitHash == null) {
        throw new CommandLineException(
            String.format(
                "Property [%s] is not set and the command line flag [%s] was not passed.",
                BUCK_GIT_COMMIT_KEY, BUCK_BINARY_STRING_ARG));
      }

      return BuckVersionUtil.createFromGitHash(gitHash);
    }

    Path binaryPath = Paths.get(buckBinary);
    if (!Files.isRegularFile(binaryPath)) {
      throw new CommandLineException(
          String.format(
              "Buck binary [%s] passed under flag [%s] does not exist.",
              binaryPath, BUCK_BINARY_STRING_ARG));
    }

    return BuckVersionUtil.createFromLocalBinary(binaryPath);
  }
}
