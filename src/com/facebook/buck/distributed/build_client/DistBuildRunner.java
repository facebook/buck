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

import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizer;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.ExitCode;
import com.facebook.buck.distributed.StampedeLocalBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Asynchronously runs a distributed build controller. Provides methods to kill/wait/get status
 * codes. This class is thread safe.
 */
public class DistBuildRunner {
  private static final Logger LOG = Logger.get(DistBuildRunner.class);

  private final DistBuildControllerInvoker distBuildControllerInvoker;
  private final ExecutorService executor;
  private final BuckEventBus eventBus;
  private final DistBuildService distBuildService;
  private final AtomicReference<StampedeId> stampedeIdReference;
  private final BuildEvent.DistBuildStarted started;
  private final RemoteBuildRuleSynchronizer remoteBuildSynchronizer;
  private final ImmutableSet<CountDownLatch> buildPhaseLatches;
  private final boolean waitGracefullyForDistributedBuildThreadToFinish;
  private final long distributedBuildThreadKillTimeoutSeconds;

  private final AtomicInteger distributedBuildExitCode;
  private final AtomicBoolean distributedBuildTerminated;

  @GuardedBy("this")
  @Nullable
  private Future<StampedeExecutionResult> runDistributedBuildFuture = null;

  public DistBuildRunner(
      DistBuildControllerInvoker distBuildControllerInvoker,
      ExecutorService executor,
      BuckEventBus eventBus,
      DistBuildService distBuildService,
      AtomicReference<StampedeId> stampedeIdReference,
      BuildEvent.DistBuildStarted started,
      RemoteBuildRuleSynchronizer remoteBuildSynchronizer,
      ImmutableSet<CountDownLatch> buildPhaseLatches,
      boolean waitGracefullyForDistributedBuildThreadToFinish,
      long distributedBuildThreadKillTimeoutSeconds) {
    this.distBuildControllerInvoker = distBuildControllerInvoker;
    this.executor = executor;
    this.eventBus = eventBus;
    this.distBuildService = distBuildService;
    this.stampedeIdReference = stampedeIdReference;
    this.started = started;
    this.remoteBuildSynchronizer = remoteBuildSynchronizer;
    this.buildPhaseLatches = buildPhaseLatches;
    distributedBuildTerminated = new AtomicBoolean(false);
    this.waitGracefullyForDistributedBuildThreadToFinish =
        waitGracefullyForDistributedBuildThreadToFinish;
    this.distributedBuildThreadKillTimeoutSeconds = distributedBuildThreadKillTimeoutSeconds;

    this.distributedBuildExitCode =
        new AtomicInteger(
            com.facebook.buck.distributed.ExitCode.DISTRIBUTED_PENDING_EXIT_CODE.getCode());
  }

  /** Launches dist build asynchronously */
  public synchronized void runDistBuildAsync() {
    runDistributedBuildFuture = executor.submit(this::performStampedeDistributedBuild);
  }

  /** Launches dist build synchronously */
  public synchronized void runDistBuildSync() {
    runDistributedBuildFuture = executor.submit(this::performStampedeDistributedBuild);
    try {
      runDistributedBuildFuture.get();
    } catch (ExecutionException | InterruptedException e) {
      LOG.error(e, "Stampede distributed build failed with exception");
    }
  }

  private StampedeExecutionResult performStampedeDistributedBuild() {

    // If finally {} block is reached without an exit code being set, there was an exception
    StampedeExecutionResult result =
        StampedeExecutionResult.of(ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION.getCode());

    try {
      LOG.info("Invoking DistBuildController..");
      result = distBuildControllerInvoker.runDistBuildAndReturnExitCode();
      LOG.info("Distributed build finished with exit code: " + result.getExitCode());
    } catch (IOException e) {
      LOG.error(e, "Stampede distributed build failed with exception");
      result =
          StampedeExecutionResult.builder()
              .setExitCode(ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION.getCode())
              .setException(e)
              .build();
    } catch (InterruptedException e) {
      LOG.warn(e, "Stampede distributed build thread was interrupted");
      result =
          StampedeExecutionResult.builder()
              .setExitCode(ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION.getCode())
              .setException(e)
              .build();
      Thread.currentThread().interrupt();
    } finally {
      LOG.info("Distributed build finished with exit code: " + result.getExitCode());

      distributedBuildExitCode.compareAndSet(
          ExitCode.DISTRIBUTED_PENDING_EXIT_CODE.getCode(), result.getExitCode());

      // If remote build succeeded, always set the code.
      // This is important for allowing post build analysis to proceed.
      if (result.getExitCode() == 0) {
        distributedBuildExitCode.set(result.getExitCode());
      }

      if (distributedBuildExitCode.get()
          == ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION.getCode()) {
        LOG.warn(
            "Received exception in distributed build monitoring thread. Attempting to terminate distributed build..");
        terminateDistributedBuildJob(
            BuildStatus.FAILED,
            "Exception thrown in Stampede client distributed build monitoring thread.");
      }

      // Local build should not be blocked, even if one of the distributed stages failed.
      remoteBuildSynchronizer.signalCompletionOfRemoteBuild(
          distributedBuildExitCode.get() == ExitCode.SUCCESSFUL.getCode());
      // We probably already have sent the DistBuildFinishedEvent but in case it slipped through the
      // exceptions, send it again.
      eventBus.post(
          BuildEvent.distBuildFinished(Preconditions.checkNotNull(started), result.getExitCode()));

      // Whichever build phase is executing should now move to the final stage.
      buildPhaseLatches.forEach(latch -> latch.countDown());
    }

    return result;
  }

  /**
   * Performs cleanup of distributed build when local build finishes first.
   *
   * @throws InterruptedException
   */
  public synchronized void cancelAsLocalBuildFinished(
      boolean localBuildSucceeded, int localBuildExitCode) throws InterruptedException {
    Preconditions.checkNotNull(
        runDistributedBuildFuture, "Cannot cancel build that hasn't started");

    String statusString =
        localBuildSucceeded
            ? "finished"
            : String.format("failed [exitCode=%d]", localBuildExitCode);
    eventBus.post(new StampedeLocalBuildStatusEvent(statusString));

    if (finishedSuccessfully() && !waitGracefullyForDistributedBuildThreadToFinish) {
      runDistributedBuildFuture.cancel(true); // Probably it's already dead
      return;
    }

    if (stillPending()) {
      setLocalBuildFinishedFirstExitCode();
      String statusMessage =
          String.format("The build %s locally before distributed build finished.", statusString);
      terminateDistributedBuildJob(
          localBuildSucceeded ? BuildStatus.FINISHED_SUCCESSFULLY : BuildStatus.FAILED,
          statusMessage);
    }

    if (waitGracefullyForDistributedBuildThreadToFinish) {
      waitUntilFinished();
    } else {
      waitUntilFinishedOrKillOnTimeout();
    }
  }

  private synchronized void waitUntilFinished() throws InterruptedException {
    try {
      LOG.info("Waiting for distributed build thread to finish.");
      Preconditions.checkNotNull(runDistributedBuildFuture).get();
    } catch (ExecutionException e) {
      LOG.error(e, "Exception thrown whilst waiting for distributed build thread to finish");
    }
  }

  private synchronized void waitUntilFinishedOrKillOnTimeout() throws InterruptedException {
    try {
      Preconditions.checkNotNull(runDistributedBuildFuture)
          .get(distributedBuildThreadKillTimeoutSeconds, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      LOG.warn(
          e,
          "Distributed build failed to finish within timeout after getting killed. "
              + "Abandoning now.");
      runDistributedBuildFuture.cancel(true);
    }
  }

  /** Prints any infra failures to the console */
  public void printAnyFailures() throws InterruptedException {
    StampedeExecutionResult result = null;
    try {
      Future<StampedeExecutionResult> executionResultFuture =
          Preconditions.checkNotNull(runDistributedBuildFuture);
      if (executionResultFuture.isCancelled()) {
        return;
      }
      result =
          executionResultFuture.get(distributedBuildThreadKillTimeoutSeconds, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      LOG.warn("Failed to get ExecutionResult within timeout. Skipping printing failures");
      return;
    }

    if (!result.getException().isPresent()) {
      return;
    }

    Throwable exception = result.getException().get();
    String stackTrace = Throwables.getStackTraceAsString(exception);
    String stage = result.getErrorStage().isPresent() ? result.getErrorStage().get() : "unknown";
    String errorMessage =
        String.format(
            "- STAMPEDE DISTRIBUTED BUILD FAILED AT [%s] STAGE. EXCEPTION:\n%s",
            stage.toUpperCase(), stackTrace);

    if (result.getHandleGracefully()) {
      LOG.warn(errorMessage);
    } else {
      eventBus.post(ConsoleEvent.severe(errorMessage));
    }
  }

  private void terminateDistributedBuildJob(BuildStatus finalStatus, String statusMessage) {
    StampedeId stampedeId = Preconditions.checkNotNull(stampedeIdReference.get());
    if (stampedeId.getId().equals(StampedeBuildClient.PENDING_STAMPEDE_ID)) {
      LOG.warn("Can't terminate distributed build as no Stampede ID yet. Skipping..");
      return; // There is no ID yet, so we can't kill anything
    }

    boolean alreadyTerminated = !distributedBuildTerminated.compareAndSet(false, true);
    if (alreadyTerminated) {
      return; // Distributed build has already been terminated.
    }

    LOG.info(
        String.format("Terminating distributed build with Stampede ID [%s].", stampedeId.getId()));

    try {
      distBuildService.setFinalBuildStatus(stampedeId, finalStatus, statusMessage);
    } catch (IOException | RuntimeException e) {
      LOG.warn(e, "Failed to terminate distributed build.");
    }
  }

  /** @return True if distributed build has finished successfully */
  public boolean finishedSuccessfully() {
    return distributedBuildExitCode.get() == ExitCode.SUCCESSFUL.getCode();
  }

  private boolean stillPending() {
    return distributedBuildExitCode.get() == ExitCode.DISTRIBUTED_PENDING_EXIT_CODE.getCode();
  }

  private void setLocalBuildFinishedFirstExitCode() {
    distributedBuildExitCode.compareAndSet(
        ExitCode.DISTRIBUTED_PENDING_EXIT_CODE.getCode(),
        ExitCode.LOCAL_BUILD_FINISHED_FIRST.getCode());
  }

  public int getExitCode() {
    return distributedBuildExitCode.get();
  }

  public boolean failed() {
    return !stillPending() && !finishedSuccessfully();
  }
}
