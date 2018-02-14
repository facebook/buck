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
package com.facebook.buck.distributed.build_client;

import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.RemoteBuildRuleSynchronizer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the different parts of the build that run on client that launched Stampede. This
 * includes local build phases (racing, synchronized), and monitoring of distributed build.
 */
public class StampedeBuildClient {
  private static final Logger LOG = Logger.get(StampedeBuildClient.class);
  public static final String PENDING_STAMPEDE_ID = "PENDING_STAMPEDE_ID";

  private final RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer;
  private final AtomicReference<StampedeId> stampedeIdReference =
      new AtomicReference<>(createPendingStampedeId());
  private final BuckEventBus eventBus;

  private final LocalBuildRunner racerBuildRunner;
  private final LocalBuildRunner synchronizedBuildRunner;
  private final DistBuildRunner distBuildRunner;

  @VisibleForTesting
  public StampedeBuildClient(
      BuckEventBus eventBus,
      RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer,
      ExecutorService executorForLocalBuild,
      ExecutorService executorForDistBuildController,
      DistBuildService distBuildService,
      BuildEvent.DistBuildStarted distBuildStartedEvent,
      CountDownLatch waitForRacingBuildCalledLatch,
      CountDownLatch waitForSynchronizedBuildCalledLatch,
      LocalBuildExecutorInvoker localBuildExecutorInvoker,
      DistBuildControllerInvoker distBuildControllerInvoker,
      boolean waitGracefullyForDistributedBuildThreadToFinish,
      Optional<StampedeId> stampedeId) {
    stampedeId.ifPresent(id -> this.stampedeIdReference.set(id));
    this.eventBus = eventBus;
    this.remoteBuildRuleSynchronizer = remoteBuildRuleSynchronizer;
    this.racerBuildRunner =
        createStampedeLocalBuildRunner(
            executorForLocalBuild,
            localBuildExecutorInvoker,
            "racer",
            false,
            Optional.of(waitForRacingBuildCalledLatch));
    this.synchronizedBuildRunner =
        createStampedeLocalBuildRunner(
            executorForLocalBuild,
            localBuildExecutorInvoker,
            "synchronized",
            true,
            Optional.of(waitForSynchronizedBuildCalledLatch));
    this.distBuildRunner =
        createStampedeDistBuildExecutor(
            distBuildControllerInvoker,
            executorForDistBuildController,
            distBuildService,
            distBuildStartedEvent,
            waitGracefullyForDistributedBuildThreadToFinish);
  }

  public StampedeBuildClient(
      BuckEventBus eventBus,
      ListeningExecutorService executorForLocalBuild,
      ListeningExecutorService executorForDistBuildController,
      DistBuildService distBuildService,
      BuildEvent.DistBuildStarted distBuildStartedEvent,
      LocalBuildExecutorInvoker localBuildExecutorInvoker,
      DistBuildControllerArgs.Builder distBuildControllerAgrsBuilder,
      DistBuildControllerInvocationArgs distBuildControllerInvocationArgs,
      boolean waitGracefullyForDistributedBuildThreadToFinish) {
    this.eventBus = eventBus;
    this.remoteBuildRuleSynchronizer = new RemoteBuildRuleSynchronizer();
    this.racerBuildRunner =
        createStampedeLocalBuildRunner(
            executorForLocalBuild, localBuildExecutorInvoker, "racer", false, Optional.empty());
    this.synchronizedBuildRunner =
        createStampedeLocalBuildRunner(
            executorForLocalBuild,
            localBuildExecutorInvoker,
            "synchronized",
            true,
            Optional.empty());
    this.distBuildRunner =
        createStampedeDistBuildExecutor(
            createDistBuildControllerInvoker(
                createDistBuildController(
                    remoteBuildRuleSynchronizer,
                    stampedeIdReference,
                    distBuildControllerAgrsBuilder),
                distBuildControllerInvocationArgs),
            executorForDistBuildController,
            distBuildService,
            distBuildStartedEvent,
            waitGracefullyForDistributedBuildThreadToFinish);
  }

  /**
   * Kicks off distributed build, then runs a multi-phase local build which.
   *
   * @throws InterruptedException
   */
  public int build(boolean skipRacingPhase, boolean localBuildFallbackEnabled)
      throws InterruptedException {
    LOG.info(
        String.format(
            "Stampede build client starting. skipRacingPhase=[%s], localBuildFallbackEnabled=[%s].",
            skipRacingPhase, localBuildFallbackEnabled));

    // Kick off the distributed build
    distBuildRunner.runDistBuildAsync();

    boolean proceedToLocalSynchronizedBuildPhase = skipRacingPhase;
    if (!skipRacingPhase) {
      proceedToLocalSynchronizedBuildPhase =
          !RacingBuildPhase.run(
              distBuildRunner,
              racerBuildRunner,
              remoteBuildRuleSynchronizer,
              localBuildFallbackEnabled,
              eventBus);
    }

    if (proceedToLocalSynchronizedBuildPhase) {
      SynchronizedBuildPhase.run(
          distBuildRunner,
          synchronizedBuildRunner,
          remoteBuildRuleSynchronizer,
          localBuildFallbackEnabled,
          eventBus);
    }

    int localExitCode =
        synchronizedBuildRunner.isFinished()
            ? synchronizedBuildRunner.getExitCode()
            : racerBuildRunner.getExitCode();
    LOG.info(
        String.format(
            "All Stampede local builds finished. Final local exit code [%d]", localExitCode));

    return localExitCode;
  }

  public int getDistBuildExitCode() {
    return distBuildRunner.getExitCode();
  }

  public StampedeId getStampedeId() {
    return Preconditions.checkNotNull(stampedeIdReference.get());
  }

  /**
   * ****************************** Helpers to create dependencies ******************************
   */
  private LocalBuildRunner createStampedeLocalBuildRunner(
      ExecutorService executorForLocalBuild,
      LocalBuildExecutorInvoker localBuildExecutorInvoker,
      String localBuildType,
      boolean isDownloadHeavyBuild,
      Optional<CountDownLatch> waitForBuildCalledLatch) {
    Preconditions.checkNotNull(remoteBuildRuleSynchronizer);
    return new LocalBuildRunner(
        executorForLocalBuild,
        localBuildExecutorInvoker,
        localBuildType,
        isDownloadHeavyBuild,
        remoteBuildRuleSynchronizer,
        waitForBuildCalledLatch);
  }

  private DistBuildRunner createStampedeDistBuildExecutor(
      DistBuildControllerInvoker distBuildControllerInvoker,
      ExecutorService executorToRunDistBuildController,
      DistBuildService distBuildService,
      BuildEvent.DistBuildStarted distBuildStartedEvent,
      boolean waitGracefullyForDistributedBuildThreadToFinish) {
    Preconditions.checkNotNull(eventBus);
    Preconditions.checkNotNull(racerBuildRunner);
    Preconditions.checkNotNull(synchronizedBuildRunner);
    Preconditions.checkNotNull(remoteBuildRuleSynchronizer);
    Preconditions.checkNotNull(stampedeIdReference);

    ImmutableSet<CountDownLatch> buildPhaseLatches =
        ImmutableSet.of(
            racerBuildRunner.getBuildPhaseLatch(), synchronizedBuildRunner.getBuildPhaseLatch());

    return new DistBuildRunner(
        distBuildControllerInvoker,
        executorToRunDistBuildController,
        eventBus,
        distBuildService,
        stampedeIdReference,
        distBuildStartedEvent,
        remoteBuildRuleSynchronizer,
        buildPhaseLatches,
        waitGracefullyForDistributedBuildThreadToFinish);
  }

  private static DistBuildControllerInvoker createDistBuildControllerInvoker(
      final DistBuildController distBuildController,
      final DistBuildControllerInvocationArgs distBuildControllerInvocationArgs) {
    return () -> {
      DistBuildController.ExecutionResult distBuildResult =
          distBuildController.executeAndPrintFailuresToEventBus(
              distBuildControllerInvocationArgs.getExecutorService(),
              distBuildControllerInvocationArgs.getProjectFilesystem(),
              distBuildControllerInvocationArgs.getFileHashCache(),
              distBuildControllerInvocationArgs.getInvocationInfo(),
              distBuildControllerInvocationArgs.getBuildMode(),
              distBuildControllerInvocationArgs.getNumberOfMinions(),
              distBuildControllerInvocationArgs.getRepository(),
              distBuildControllerInvocationArgs.getTenantId(),
              distBuildControllerInvocationArgs.getRuleKeyCalculatorFuture());

      return distBuildResult.exitCode;
    };
  }

  private static DistBuildController createDistBuildController(
      RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer,
      AtomicReference<StampedeId> stampedeIdReference,
      DistBuildControllerArgs.Builder distBuildControllerArgsBuilder) {
    return new DistBuildController(
        distBuildControllerArgsBuilder
            .setRemoteBuildRuleCompletionNotifier(remoteBuildRuleSynchronizer)
            .setStampedeIdReference(stampedeIdReference)
            .build());
  }

  private StampedeId createPendingStampedeId() {
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(PENDING_STAMPEDE_ID);
    return stampedeId;
  }
}
