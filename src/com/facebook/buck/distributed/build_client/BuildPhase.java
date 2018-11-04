/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_client;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD;
import static com.facebook.buck.distributed.thrift.BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR;

import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionNotifier;
import com.facebook.buck.core.build.engine.delegate.CachingBuildEngineDelegate;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.BuildSlaveEventWrapper;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.DistLocalBuildMode;
import com.facebook.buck.distributed.RuleKeyUtils;
import com.facebook.buck.distributed.build_slave.CoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.distributed.build_slave.CoordinatorModeRunner;
import com.facebook.buck.distributed.build_slave.DelegateAndGraphs;
import com.facebook.buck.distributed.build_slave.HealthCheckStatsTracker;
import com.facebook.buck.distributed.build_slave.MultiSlaveBuildModeRunnerFactory;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.RuleKeyCalculatedEvent;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/** The build phase. */
public class BuildPhase {

  /** The result from a build. */
  public class BuildResult {

    private final BuildJob finalBuildJob;
    private final List<BuildSlaveStatus> buildSlaveStatusList;
    private final Set<String> mismatchingBuildTargets;

    public BuildResult(
        BuildJob finalBuildJob,
        List<BuildSlaveStatus> buildSlaveStatusList,
        Set<String> mismatchingBuildTargets) {
      this.finalBuildJob = finalBuildJob;
      this.buildSlaveStatusList = buildSlaveStatusList;
      this.mismatchingBuildTargets = mismatchingBuildTargets;
    }

    public BuildJob getFinalBuildJob() {
      return finalBuildJob;
    }

    public List<BuildSlaveStatus> getBuildSlaveStatusList() {
      return buildSlaveStatusList;
    }

    public Set<String> getMismatchingBuildTargets() {
      return mismatchingBuildTargets;
    }
  }

  private static final Logger LOG = Logger.get(BuildPhase.class);

  private static final int DEFAULT_WAIT_FOR_ALL_WORKER_FINAL_STATUS_TIMEOUT_MILLIS = 6000;
  private static final int DEFAULT_WAIT_FOR_ALL_BUILD_EVENTS_TIMEOUT_MILLIS = 5000;

  private final BuildExecutorArgs buildExecutorArgs;
  private final ImmutableSet<BuildTarget> topLevelTargets;
  private final ActionAndTargetGraphs buildGraphs;
  private final Optional<CachingBuildEngineDelegate> cachingBuildEngineDelegate;
  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final LogStateTracker distBuildLogStateTracker;
  private final ScheduledExecutorService scheduler;
  private final int statusPollIntervalMillis;
  private RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier;
  private final Set<String> seenSlaveRunIds;
  private final Map<BuildSlaveRunId, Integer> nextEventIdBySlaveRunId;
  private final Clock clock;
  private final BuildRuleEventManager buildRuleEventManager;
  private final ConsoleEventsDispatcher consoleEventsDispatcher;
  private final int waitForAllWorkerFinalStatusTimeoutMillis;
  private final int waitForAllBuildEventsTimeoutMillis;

  private volatile long firstFinishedBuildStatusReceivedTs = -1;

  @VisibleForTesting
  protected BuildPhase(
      BuildExecutorArgs buildExecutorArgs,
      ImmutableSet<BuildTarget> topLevelTargets,
      ActionAndTargetGraphs buildGraphs,
      Optional<CachingBuildEngineDelegate> cachingBuildEngineDelegate,
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      LogStateTracker distBuildLogStateTracker,
      ScheduledExecutorService scheduler,
      int statusPollIntervalMillis,
      RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier,
      ConsoleEventsDispatcher consoleEventsDispatcher,
      Clock clock,
      int waitForAllWorkerFinalStatusTimeoutMillis,
      int waitForAllBuildEventsTimeoutMillis) {
    this.buildExecutorArgs = buildExecutorArgs;
    this.topLevelTargets = topLevelTargets;
    this.buildGraphs = buildGraphs;
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.distBuildLogStateTracker = distBuildLogStateTracker;
    this.scheduler = scheduler;
    this.statusPollIntervalMillis = statusPollIntervalMillis;
    this.remoteBuildRuleCompletionNotifier = remoteBuildRuleCompletionNotifier;
    this.seenSlaveRunIds = new HashSet<>();
    this.nextEventIdBySlaveRunId = new HashMap<>();
    this.clock = clock;
    this.consoleEventsDispatcher = consoleEventsDispatcher;
    this.buildRuleEventManager =
        new BuildRuleEventManager(
            remoteBuildRuleCompletionNotifier,
            clock,
            new DistBuildConfig(buildExecutorArgs.getBuckConfig())
                .getCacheSynchronizationSafetyMarginMillis());

    this.waitForAllWorkerFinalStatusTimeoutMillis = waitForAllWorkerFinalStatusTimeoutMillis;
    this.waitForAllBuildEventsTimeoutMillis = waitForAllBuildEventsTimeoutMillis;
  }

  public BuildPhase(
      BuildExecutorArgs buildExecutorArgs,
      ImmutableSet<BuildTarget> topLevelTargets,
      ActionAndTargetGraphs buildGraphs,
      Optional<CachingBuildEngineDelegate> cachingBuildEngineDelegate,
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      LogStateTracker distBuildLogStateTracker,
      ScheduledExecutorService scheduler,
      int statusPollIntervalMillis,
      RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier,
      ConsoleEventsDispatcher consoleEventsDispatcher) {
    this(
        buildExecutorArgs,
        topLevelTargets,
        buildGraphs,
        cachingBuildEngineDelegate,
        distBuildService,
        distBuildClientStats,
        distBuildLogStateTracker,
        scheduler,
        statusPollIntervalMillis,
        remoteBuildRuleCompletionNotifier,
        consoleEventsDispatcher,
        new DefaultClock(),
        DEFAULT_WAIT_FOR_ALL_WORKER_FINAL_STATUS_TIMEOUT_MILLIS,
        DEFAULT_WAIT_FOR_ALL_BUILD_EVENTS_TIMEOUT_MILLIS);
  }

  @SuppressWarnings("CheckReturnValue") // submit result is not used
  private void runLocalCoordinatorAsync(
      ListeningExecutorService executorService,
      StampedeId stampedeId,
      InvocationInfo invocationInfo,
      ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator) {
    Preconditions.checkState(cachingBuildEngineDelegate.isPresent());
    DistBuildConfig distBuildConfig = new DistBuildConfig(buildExecutorArgs.getBuckConfig());

    ListenableFuture<DelegateAndGraphs> delegateAndGraphs =
        Futures.immediateFuture(
            DelegateAndGraphs.builder()
                .setActionGraphAndBuilder(buildGraphs.getActionGraphAndBuilder())
                .setCachingBuildEngineDelegate(cachingBuildEngineDelegate.get())
                .setTargetGraph(buildGraphs.getTargetGraphForDistributedBuild().getTargetGraph())
                .build());

    CoordinatorBuildRuleEventsPublisher finishedRulePublisher =
        new CoordinatorBuildRuleEventsPublisher() {
          @Override
          public void updateCoordinatorBuildProgress(CoordinatorBuildProgress progress) {
            consoleEventsDispatcher.postDistBuildProgressEvent(progress);
          }

          @Override
          public void createBuildRuleStartedEvents(ImmutableList<String> startedTargets) {
            for (String target : startedTargets) {
              buildRuleEventManager.recordBuildRuleStartedEvent(target);
            }
          }

          @Override
          public void createBuildRuleCompletionEvents(ImmutableList<String> finishedTargets) {
            long currentTimeMillis = clock.currentTimeMillis();
            for (String target : finishedTargets) {
              buildRuleEventManager.recordBuildRuleFinishedEvent(currentTimeMillis, target);
            }
          }

          @Override
          public void createMostBuildRulesCompletedEvent() {
            buildRuleEventManager.mostBuildRulesFinishedEventReceived();
          }

          @Override
          public void createBuildRuleUnlockedEvents(ImmutableList<String> unlockedTargets) {
            for (String target : unlockedTargets) {
              buildRuleEventManager.recordBuildRuleUnlockedEvent(target);
            }
          }
        };

    CoordinatorModeRunner coordinator =
        MultiSlaveBuildModeRunnerFactory.createCoordinator(
            delegateAndGraphs,
            topLevelTargets.asList(),
            distBuildConfig,
            distBuildService,
            stampedeId,
            Optional.of(invocationInfo.getBuildId()),
            false,
            invocationInfo.getLogDirectoryPath(),
            finishedRulePublisher,
            buildExecutorArgs.getBuckEventBus(),
            executorService,
            buildExecutorArgs.getArtifactCacheFactory().remoteOnlyInstance(true, false),
            localRuleKeyCalculator,
            // TODO(shivanker): Make health-check stats work.
            new HealthCheckStatsTracker(),
            // TODO(shivanker): Make timing stats work.
            Optional.empty(),
            Optional.empty());

    executorService.submit(
        () -> {
          try {
            coordinator.runWithHeartbeatServiceAndReturnExitCode(distBuildConfig);
          } catch (IOException | InterruptedException e) {
            // This special case of processing IOException and InterruptedException is only to
            // maintain backwards compatibility
            // TODO (buck_team): process exceptions in a good structured way
            LOG.error(e, "Coordinator failed with Exception.");
            // throwing inside an executor won't help.
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  /** Run the build while updating the console messages. */
  public BuildResult runDistBuildAndUpdateConsoleStatus(
      ListeningExecutorService executorService,
      StampedeId stampedeId,
      BuildMode buildMode,
      DistLocalBuildMode distLocalBuildMode,
      InvocationInfo invocationInfo,
      ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator)
      throws IOException, InterruptedException, ExecutionException {
    distBuildClientStats.startTimer(PERFORM_DISTRIBUTED_BUILD);

    BuildJob job =
        distBuildService.startBuild(
            stampedeId, !buildMode.equals(DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR));
    LOG.info("Started job. Build status: " + job.getStatus());

    ListenableFuture<List<Pair<BuildRule, RuleKey>>> ruleKeyPairsFutureFuture =
        Futures.immediateFuture(null);

    if (distLocalBuildMode.equals(DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK)) {
      ruleKeyPairsFutureFuture =
          Futures.transformAsync(
              localRuleKeyCalculator,
              ruleKeyCalculator ->
                  RuleKeyUtils.calculateDefaultRuleKeys(
                      buildGraphs.getActionGraphAndBuilder().getActionGraphBuilder(),
                          ruleKeyCalculator,
                      buildExecutorArgs.getBuckEventBus(), topLevelTargets),
              Objects.requireNonNull(buildExecutorArgs.getExecutors().get(ExecutorPool.CPU)));
    }

    if (distLocalBuildMode.equals(DistLocalBuildMode.FIRE_AND_FORGET)) {
      LOG.info("Fire-and-forget mode: returning after build status is:started. " + job.getStatus());
      return new BuildResult(job, ImmutableList.of(), ImmutableSet.of());
    }

    nextEventIdBySlaveRunId.clear();
    ScheduledFuture<?> distBuildStatusUpdatingFuture =
        scheduler.scheduleWithFixedDelay(
            () -> {
              try {
                fetchBuildInformationFromServerAndPublishPendingEvents(job, executorService);
              } catch (InterruptedException e) {
                LOG.warn(
                    e, "fetchBuildInformationFromServerAndPublishPendingEvents was interrupted");
                Thread.currentThread().interrupt();
                throw new RuntimeException(e); // Ensure we don't schedule any more fetches
              }
            },
            0,
            statusPollIntervalMillis,
            TimeUnit.MILLISECONDS);

    if (buildMode.equals(DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR)) {
      runLocalCoordinatorAsync(executorService, stampedeId, invocationInfo, localRuleKeyCalculator);
    }

    BuildJob finalJob = null;
    List<BuildSlaveStatus> buildSlaveStatusList = null;
    try {
      distBuildStatusUpdatingFuture.get();
      throw new RuntimeException("Unreachable State.");
    } catch (InterruptedException ex) {
      // Important to cancel distBuildStatusUpdatingFuture, otherwise the async task will
      // keep blocking the ScheduledExecutorService that it runs inside
      distBuildStatusUpdatingFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw ex;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof JobCompletedException) {
        // Everything is awesome.
        JobCompletedException jobCompletedException = (JobCompletedException) e.getCause();
        finalJob = jobCompletedException.getDistBuildJob();
        buildSlaveStatusList =
            jobCompletedException.getBuildSlaveStatuses().orElse(ImmutableList.of());
      } else {
        throw new HumanReadableException(e, "Failed to fetch build information from server.");
      }
    } finally {
      distBuildClientStats.stopTimer(PERFORM_DISTRIBUTED_BUILD);

      // Remote build is now done, so ensure local build is unlocked for all build rules.
      remoteBuildRuleCompletionNotifier.signalCompletionOfRemoteBuild(
          finalJob != null && finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY));
    }

    Set<String> mismatchingBuildTargets =
        checkForRuleKeyMismatches(distLocalBuildMode, ruleKeyPairsFutureFuture);

    return new BuildResult(finalJob, buildSlaveStatusList, mismatchingBuildTargets);
  }

  @Nonnull
  private Set<String> checkForRuleKeyMismatches(
      DistLocalBuildMode distLocalBuildMode,
      ListenableFuture<List<Pair<BuildRule, RuleKey>>> ruleKeyPairsFutureFuture)
      throws ExecutionException, InterruptedException {
    Set<String> mismatchingBuildTargets = new HashSet<>();
    if (!distLocalBuildMode.equals(DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK)) {
      return mismatchingBuildTargets;
    }
    List<Pair<BuildRule, RuleKey>> localRuleKeyPairs = ruleKeyPairsFutureFuture.get();
    List<RuleKeyCalculatedEvent> remoteRuleKeys =
        buildRuleEventManager.getRuleKeyCalculatedEvents();

    Map<String, String> localRulesToKeys = new HashMap<>();

    for (Pair<BuildRule, RuleKey> localRuleKeyPair : localRuleKeyPairs) {
      localRulesToKeys.put(
          localRuleKeyPair.getFirst().getFullyQualifiedName(),
          localRuleKeyPair.getSecond().getHashCode().toString());
    }

    Set<String> remoteBuildTargets = new HashSet<>();
    for (RuleKeyCalculatedEvent remoteRuleAndKey : remoteRuleKeys) {
      String remoteBuildTarget = remoteRuleAndKey.getBuildTarget();
      remoteBuildTargets.add(remoteBuildTarget);
      if (localRulesToKeys.containsKey(remoteBuildTarget)
          && !localRulesToKeys
              .get(remoteBuildTarget)
              .equals(remoteRuleAndKey.getDefaultRuleKey())) {
        LOG.warn(
            "Rule key for [%s] mismatched locally and remotely. Local [%s]. Remote [%s]",
            remoteBuildTarget,
            localRulesToKeys.containsKey(remoteBuildTarget),
            remoteRuleAndKey.getDefaultRuleKey());
        mismatchingBuildTargets.add(remoteBuildTarget);
      }
    }

    SetView<String> buildTargetsOnlyBuildInOneLocation =
        Sets.symmetricDifference(localRulesToKeys.keySet(), remoteBuildTargets);
    for (String buildTarget : buildTargetsOnlyBuildInOneLocation) {
      LOG.warn("Rule [%s] was only built in one location", buildTarget);
      mismatchingBuildTargets.add(buildTarget);
    }

    return mismatchingBuildTargets;
  }

  private BuildJob fetchBuildInformationFromServerAndPublishPendingEvents(
      BuildJob job, ListeningExecutorService networkExecutorService) throws InterruptedException {
    StampedeId stampedeId = job.getStampedeId();

    try {
      job = distBuildService.getCurrentBuildJobState(stampedeId);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    LOG.info("Got build status: " + job.getStatus());

    if (!job.isSetBuildSlaves()) {
      consoleEventsDispatcher.postDistBuildStatusEvent(job, ImmutableList.of());
      checkTerminateScheduledUpdates(job, Optional.empty());
      return job;
    }

    for (BuildSlaveInfo slave : job.getBuildSlaves()) {
      String runIdString = slave.getBuildSlaveRunId().getId();
      if (!seenSlaveRunIds.contains(slave.getBuildSlaveRunId().getId())) {
        seenSlaveRunIds.add(runIdString);
        LOG.info(
            "New slave server attached to build. (RunId: [%s], Hostname: [%s])",
            runIdString, slave.getHostname());
      }
    }

    // TODO(alisdair,shivanker): if job just completed (checkTerminateScheduledUpdates),
    // we could have missed the final few events.
    ListenableFuture<?> slaveEventsFuture =
        fetchAndPostBuildSlaveEventsAsync(job, networkExecutorService);
    ListenableFuture<List<BuildSlaveStatus>> slaveStatusesFuture =
        fetchBuildSlaveStatusesAsync(job, networkExecutorService);
    ListenableFuture<?> logStreamingFuture =
        fetchAndProcessRealTimeSlaveLogsAsync(job, networkExecutorService);

    List<BuildSlaveStatus> slaveStatuses = ImmutableList.of();
    try {
      slaveStatuses = slaveStatusesFuture.get();
      consoleEventsDispatcher.postDistBuildStatusEvent(job, slaveStatuses);
      slaveEventsFuture.get();
      logStreamingFuture.get();
    } catch (InterruptedException ex) {
      // Ensure all async work is interrupted too.
      slaveStatusesFuture.cancel(true);
      slaveEventsFuture.cancel(true);
      logStreamingFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw ex;
    } catch (ExecutionException e) {
      LOG.error(e, "Failed to get slave statuses, events or logs.");
    }

    buildRuleEventManager.publishCacheSynchronizedBuildRuleFinishedEvents();

    checkTerminateScheduledUpdates(job, Optional.of(slaveStatuses));
    return job;
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndPostBuildSlaveEventsAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetBuildSlaves()) {
      return Futures.immediateFuture(null);
    }

    StampedeId stampedeId = job.getStampedeId();
    List<BuildSlaveEventsQuery> fetchEventQueries = new LinkedList<>();

    for (BuildSlaveInfo slave : job.getBuildSlaves()) {
      BuildSlaveRunId runId = slave.getBuildSlaveRunId();
      fetchEventQueries.add(
          distBuildService.createBuildSlaveEventsQuery(
              stampedeId, runId, nextEventIdBySlaveRunId.getOrDefault(runId, 0)));
    }
    ListenableFuture<List<BuildSlaveEventWrapper>> fetchEventsFuture =
        networkExecutorService.submit(
            () -> {
              try {
                return distBuildService.multiGetBuildSlaveEvents(fetchEventQueries);
              } catch (IOException e) {
                LOG.error(e, "Fetching build slave events failed. Returning empty list.");
                return Lists.newArrayList();
              }
            });

    ListenableFuture<?> postEventsFuture =
        Futures.transform(
            fetchEventsFuture,
            events -> {

              // Sort such that all events from the same RunId come together, and in increasing
              // order
              // of their sequence IDs. Also, we cannot directly sort sequenceIdAndEvents as it
              // might
              // be an ImmutableList, hence we make it a stream.
              events =
                  events
                      .stream()
                      .sorted(
                          (w1, w2) -> {
                            BuildSlaveRunId runId1 = w1.getBuildSlaveRunId();
                            BuildSlaveRunId runId2 = w1.getBuildSlaveRunId();

                            int result = runId1.compareTo(runId2);
                            if (result == 0) {
                              return Integer.compare(w1.getEventNumber(), w2.getEventNumber());
                            }

                            return result;
                          })
                      .collect(Collectors.toList());

              LOG.info(String.format("Processing [%d] slave events", events.size()));

              long currentTimeMillis = clock.currentTimeMillis();
              for (BuildSlaveEventWrapper wrapper : events) {
                BuildSlaveEvent slaveEvent = wrapper.getEvent();
                nextEventIdBySlaveRunId.put(
                    wrapper.getBuildSlaveRunId(), wrapper.getEventNumber() + 1);
                switch (slaveEvent.getEventType()) {
                  case RULE_KEY_CALCULATED_EVENT:
                    buildRuleEventManager.recordRuleKeyCalculatedEvent(
                        slaveEvent.getRuleKeyCalculatedEvent());
                    break;
                  case CONSOLE_EVENT:
                    ConsoleEvent consoleEvent = DistBuildUtil.createConsoleEvent(slaveEvent);
                    consoleEventsDispatcher.postConsoleEvent(consoleEvent);
                    break;
                  case BUILD_RULE_STARTED_EVENT:
                    buildRuleEventManager.recordBuildRuleStartedEvent(
                        slaveEvent.getBuildRuleStartedEvent().getBuildTarget());
                    break;
                  case BUILD_RULE_FINISHED_EVENT:
                    buildRuleEventManager.recordBuildRuleFinishedEvent(
                        currentTimeMillis, slaveEvent.getBuildRuleFinishedEvent().getBuildTarget());
                    break;
                  case ALL_BUILD_RULES_FINISHED_EVENT:
                    buildRuleEventManager.recordAllBuildRulesFinishedEvent();
                    break;
                  case MOST_BUILD_RULES_FINISHED_EVENT:
                    buildRuleEventManager.mostBuildRulesFinishedEventReceived();
                    break;
                  case COORDINATOR_BUILD_PROGRESS_EVENT:
                    consoleEventsDispatcher.postDistBuildProgressEvent(
                        slaveEvent.getCoordinatorBuildProgressEvent().getBuildProgress());
                    break;
                  case BUILD_RULE_UNLOCKED_EVENT:
                    buildRuleEventManager.recordBuildRuleUnlockedEvent(
                        slaveEvent.getBuildRuleUnlockedEvent().getBuildTarget());
                    break;
                  case UNKNOWN:
                    LOG.error(
                        String.format(
                            "Unknown type of BuildSlaveEvent received: [%d]",
                            slaveEvent.getEventType().getValue()));
                    break;
                }
              }
              return null;
            },
            MoreExecutors.directExecutor());

    return postEventsFuture;
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndProcessRealTimeSlaveLogsAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetBuildSlaves()) {
      return Futures.immediateFuture(null);
    }

    List<LogLineBatchRequest> newLogLineRequests =
        distBuildLogStateTracker.createStreamLogRequests(job.getBuildSlaves());
    if (newLogLineRequests.isEmpty()) {
      return Futures.immediateFuture(null);
    }

    return networkExecutorService.submit(
        () -> {
          try {
            MultiGetBuildSlaveRealTimeLogsResponse slaveLogsResponse =
                distBuildService.fetchSlaveLogLines(job.getStampedeId(), newLogLineRequests);
            Preconditions.checkState(slaveLogsResponse.isSetMultiStreamLogs());

            distBuildLogStateTracker.processStreamLogs(slaveLogsResponse.getMultiStreamLogs());
          } catch (IOException e) {
            LOG.error(e, "Encountered error while streaming logs from BuildSlave(s).");
          }
        });
  }

  private void checkTerminateScheduledUpdates(
      BuildJob job, Optional<List<BuildSlaveStatus>> slaveStatuses) {
    long currentTimeMillis = clock.currentTimeMillis();

    if (BuildStatusUtil.isTerminalBuildStatus(job.getStatus())) {
      // Make a record of the first time we received terminal build job status, so that
      // if we still need to fetch more events, we have a reference point for timeouts.
      if (firstFinishedBuildStatusReceivedTs == -1) {
        firstFinishedBuildStatusReceivedTs = currentTimeMillis;
      }

      long elapseMillisSinceFirstFinishedStatus =
          currentTimeMillis - firstFinishedBuildStatusReceivedTs;

      // Top level build was set to finished status, however individual slaves might not yet
      // have marked themselves finished, so wait for this to happen before returning.
      // (Without this we have no guarantees about BuildSlaveFinishedStats being uploaded yet).
      if (!allSlavesFinished(job)) {
        if (elapseMillisSinceFirstFinishedStatus < waitForAllWorkerFinalStatusTimeoutMillis) {
          LOG.warn(
              String.format(
                  "Build has been finished for %s ms, but still missing finished status from some workers.",
                  elapseMillisSinceFirstFinishedStatus));
          return; // Events are still missing, and we haven't timed out, so poll again.
        } else {
          LOG.warn(
              String.format(
                  "%d ms elapsed since build job marked as finished, but still missing finished status from some workers.",
                  elapseMillisSinceFirstFinishedStatus));
        }
      }

      if (!buildRuleEventManager.allBuildRulesFinishedEventReceived()) {
        if (elapseMillisSinceFirstFinishedStatus < waitForAllBuildEventsTimeoutMillis) {
          LOG.warn(
              String.format(
                  "Build has been finished for %s ms, but still waiting for final build rule finished events.",
                  elapseMillisSinceFirstFinishedStatus));
          return; // Events are still missing, and we haven't timed out, so poll again.
        } else {
          LOG.warn(
              String.format(
                  "%d ms elapsed since build job marked as finished, but still missing build rule finished events.",
                  elapseMillisSinceFirstFinishedStatus));
        }
      }

      buildRuleEventManager.flushAllPendingBuildRuleFinishedEvents();

      // Terminate scheduled tasks with a custom exception to indicate success.
      throw new JobCompletedException(job, slaveStatuses);
    }
  }

  private boolean allSlavesFinished(BuildJob job) {
    // In case no slaves ever joined the build.
    if (!job.isSetBuildSlaves()) {
      return true;
    }

    for (BuildSlaveInfo slaveInfo : job.getBuildSlaves()) {
      if (!BuildStatusUtil.isTerminalBuildStatus(slaveInfo.getStatus())) {
        return false;
      }
    }
    return true;
  }

  /** Exception thrown when the build job is complete. */
  public static final class JobCompletedException extends RuntimeException {

    private final BuildJob job;
    private final Optional<List<BuildSlaveStatus>> buildSlaveStatuses;

    private JobCompletedException(
        BuildJob job, Optional<List<BuildSlaveStatus>> buildSlaveStatuses) {
      super(String.format("DistBuild job completed with status: [%s]", job.getStatus().toString()));
      this.job = job;
      this.buildSlaveStatuses = buildSlaveStatuses;
    }

    public BuildJob getDistBuildJob() {
      return job;
    }

    public Optional<List<BuildSlaveStatus>> getBuildSlaveStatuses() {
      return buildSlaveStatuses;
    }
  }

  @VisibleForTesting
  ListenableFuture<List<BuildSlaveStatus>> fetchBuildSlaveStatusesAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetBuildSlaves()) {
      return Futures.immediateFuture(ImmutableList.of());
    }

    StampedeId stampedeId = job.getStampedeId();
    List<ListenableFuture<Optional<BuildSlaveStatus>>> slaveStatusFutures = new LinkedList<>();

    // TODO(shivanker, alisdair): Replace this with a multiFetch request.
    for (BuildSlaveInfo info : job.getBuildSlaves()) {
      BuildSlaveRunId runId = info.getBuildSlaveRunId();
      slaveStatusFutures.add(
          networkExecutorService.submit(
              () -> distBuildService.fetchBuildSlaveStatus(stampedeId, runId)));
    }

    return Futures.transform(
        Futures.allAsList(slaveStatusFutures),
        slaveStatusList ->
            slaveStatusList
                .stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList()),
        MoreExecutors.directExecutor());
  }
}
