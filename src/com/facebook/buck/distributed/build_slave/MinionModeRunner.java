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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.build_slave.HeartbeatService.HeartbeatCallback;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** {@link DistBuildModeRunner} implementation for running a distributed build as minion only. */
public class MinionModeRunner extends AbstractDistBuildModeRunner {

  private static final Logger LOG = Logger.get(MinionModeRunner.class);

  private final String coordinatorAddress;

  private volatile OptionalInt coordinatorPort;
  private final int coordinatorConnectionTimeoutMillis;
  private final ListenableFuture<BuildExecutor> buildExecutorFuture;
  private final StampedeId stampedeId;
  private final MinionType minionType;
  private final BuildSlaveRunId buildSlaveRunId;
  private final long minionPollLoopIntervalMillis;

  private final BuildCompletionChecker buildCompletionChecker;
  private final ExecutorService buildExecutorService;

  private final BuckEventBus eventBus;

  private final MinionLocalBuildStateTracker buildTracker;

  // Signals to the main loop that it can stop requesting new work.
  private final AtomicBoolean finished = new AtomicBoolean(false);

  // Aggregate exit code for the minion. Non-zero if any set of build targets failed.
  private AtomicReference<ExitCode> exitCode = new AtomicReference<>(ExitCode.SUCCESS);

  @Nullable private volatile BuildExecutor buildExecutor = null;

  /** Callback when the build has completed. */
  public interface BuildCompletionChecker {

    boolean hasBuildFinished() throws IOException;
  }

  public MinionModeRunner(
      String coordinatorAddress,
      OptionalInt coordinatorPort,
      ListenableFuture<BuildExecutor> buildExecutorFuture,
      StampedeId stampedeId,
      MinionType minionType,
      BuildSlaveRunId buildSlaveRunId,
      CapacityTracker capacityTracker,
      BuildCompletionChecker buildCompletionChecker,
      long minionPollLoopIntervalMillis,
      MinionBuildProgressTracker minionBuildProgressTracker,
      int coordinatorConnectionTimeoutMillis,
      BuckEventBus eventBus) {
    this(
        coordinatorAddress,
        coordinatorPort,
        coordinatorConnectionTimeoutMillis,
        buildExecutorFuture,
        stampedeId,
        minionType,
        buildSlaveRunId,
        capacityTracker,
        buildCompletionChecker,
        minionPollLoopIntervalMillis,
        minionBuildProgressTracker,
        MostExecutors.newMultiThreadExecutor(
            new CommandThreadFactory(
                "MinionBuilderThread", GlobalStateManager.singleton().getThreadToCommandRegister()),
            capacityTracker.getMaxAvailableCapacity()),
        eventBus);
  }

  @VisibleForTesting
  public MinionModeRunner(
      String coordinatorAddress,
      OptionalInt coordinatorPort,
      int coordinatorConnectionTimeoutMillis,
      ListenableFuture<BuildExecutor> buildExecutorFuture,
      StampedeId stampedeId,
      MinionType minionType,
      BuildSlaveRunId buildSlaveRunId,
      CapacityTracker capacityTracker,
      BuildCompletionChecker buildCompletionChecker,
      long minionPollLoopIntervalMillis,
      MinionBuildProgressTracker minionBuildProgressTracker,
      ExecutorService buildExecutorService,
      BuckEventBus eventBus) {
    this.coordinatorConnectionTimeoutMillis = coordinatorConnectionTimeoutMillis;
    this.minionPollLoopIntervalMillis = minionPollLoopIntervalMillis;
    this.buildExecutorFuture = buildExecutorFuture;
    this.stampedeId = stampedeId;
    this.minionType = minionType;
    this.buildSlaveRunId = buildSlaveRunId;
    this.coordinatorAddress = coordinatorAddress;
    coordinatorPort.ifPresent(CoordinatorModeRunner::validatePort);
    this.coordinatorPort = coordinatorPort;
    this.buildCompletionChecker = buildCompletionChecker;
    this.buildExecutorService = buildExecutorService;
    this.eventBus = eventBus;
    this.buildTracker =
        new MinionLocalBuildStateTracker(minionBuildProgressTracker, capacityTracker);

    LOG.info(
        String.format(
            "Started new minion that can build [%d] work units in parallel",
            capacityTracker.getMaxAvailableCapacity()));
  }

  @Override
  public ListenableFuture<?> getAsyncPrepFuture() {
    return buildExecutorFuture;
  }

  @Override
  public ExitCode runAndReturnExitCode(HeartbeatService heartbeatService)
      throws IOException, InterruptedException {
    Preconditions.checkState(coordinatorPort.isPresent(), "Coordinator port has not been set.");
    try {
      buildExecutor = buildExecutorFuture.get();
    } catch (ExecutionException e) {
      String msg =
          String.format("Failed to get the BuildExecutor. Reason: %s", e.getCause().getMessage());
      LOG.error(e, msg);
      throw new RuntimeException(msg, e);
    }

    String minionId = DistBuildUtil.generateMinionId(buildSlaveRunId);
    try (ThriftCoordinatorClient client = newStartedThriftCoordinatorClient();
        Closeable healthCheck =
            heartbeatService.addCallback(
                "MinionIsAlive", createHeartbeatCallback(client, minionId, buildSlaveRunId))) {
      while (!finished.get()) {
        signalFinishedTargetsAndFetchMoreWork(minionId, client);
        Thread.sleep(minionPollLoopIntervalMillis);
      }

      LOG.info(String.format("Minion [%s] has exited signal/fetch work loop.", minionId));
    }

    // At this point there is no more work to schedule, so wait for the build to finish.
    buildExecutorService.shutdown();
    buildExecutorService.awaitTermination(30, TimeUnit.MINUTES);

    Objects.requireNonNull(buildExecutor).shutdown();

    return exitCode.get();
  }

  private ThriftCoordinatorClient newStartedThriftCoordinatorClient() throws IOException {
    ThriftCoordinatorClient client =
        new ThriftCoordinatorClient(
            coordinatorAddress, stampedeId, coordinatorConnectionTimeoutMillis);
    try {
      client.start(coordinatorPort.getAsInt());
    } catch (ThriftException exception) {
      handleThriftException(exception);
    }
    return client;
  }

  private HeartbeatCallback createHeartbeatCallback(
      ThriftCoordinatorClient client, String minionId, BuildSlaveRunId runId) {
    return new HeartbeatCallback() {
      @Override
      public void runHeartbeat() throws IOException {
        LOG.debug(String.format("About to send keep alive heartbeat for Minion [%s]", minionId));
        client.reportMinionAlive(minionId, runId);
      }
    };
  }

  public void setCoordinatorPort(int coordinatorPort) {
    CoordinatorModeRunner.validatePort(coordinatorPort);
    this.coordinatorPort = OptionalInt.of(coordinatorPort);
  }

  private void signalFinishedTargetsAndFetchMoreWork(
      String minionId, ThriftCoordinatorClient client) throws IOException {
    List<String> targetsToSignal = buildTracker.getTargetsToSignal();

    // Try to reserve available capacity
    int reservedCapacity = buildTracker.reserveAllAvailableCapacity();
    if (reservedCapacity == 0
        && exitCode.get() == ExitCode.SUCCESS
        && targetsToSignal.size() == 0) {
      return; // Making a request will not move the build forward, so wait a while and try again.
    }

    LOG.info(
        String.format(
            "Minion [%s] fetching work. Signalling [%d] finished targets",
            minionId, targetsToSignal.size()));

    try {
      GetWorkResponse response =
          client.getWork(
              minionId, minionType, exitCode.get().getCode(), targetsToSignal, reservedCapacity);
      if (!response.isContinueBuilding()) {
        LOG.info(String.format("Minion [%s] told to stop building.", minionId));
        finished.set(true);
      }

      buildTracker.enqueueWorkUnitsForBuildingAndCommitCapacity(response.getWorkUnits());
    } catch (ThriftException ex) {
      handleThriftException(ex);
      return;
    }

    if (!buildTracker.outstandingWorkUnitsToBuild()) {
      return; // Nothing new to build
    }

    buildExecutorService.execute(
        () -> {
          try {
            performBuildOfWorkUnits(minionId);
          } catch (Exception e) {
            LOG.error(e, "Failed whilst building targets. Terminating build. ");
            exitCode.set(ExitCode.FATAL_GENERIC);
            finished.set(true);
          }
        });
  }

  private void performBuildOfWorkUnits(String minionId) throws IOException {
    List<String> targetsToBuild = buildTracker.getTargetsToBuild();

    if (targetsToBuild.size() == 0) {
      return; // All outstanding targets have already been picked up by an earlier build thread.
    }

    LOG.info(
        String.format(
            "Minion [%s] is about to build [%d] targets", minionId, targetsToBuild.size()));
    LOG.debug(String.format("Targets: [%s]", Joiner.on(", ").join(targetsToBuild)));

    // Start the build, and get futures representing the results.
    List<BuildEngineResult> resultFutures =
        Objects.requireNonNull(buildExecutor).initializeBuild(targetsToBuild);

    // Register handlers that will ensure we free up cores as soon as a work unit is complete,
    // and signal built targets as soon as they are uploaded to the cache.
    for (BuildEngineResult resultFuture : resultFutures) {
      registerBuildRuleCompletionHandler(resultFuture);
    }

    // Wait for the targets to finish building and get the exit code.
    ExitCode lastExitCode =
        Objects.requireNonNull(buildExecutor)
            .waitForBuildToFinish(targetsToBuild, resultFutures, Optional.empty());

    LOG.info(
        String.format(
            "Minion [%s] finished with exit code [%d].", minionId, lastExitCode.getCode()));

    if (lastExitCode != ExitCode.SUCCESS) {
      exitCode.set(lastExitCode);
    }
  }

  private void registerBuildRuleCompletionHandler(BuildEngineResult resultFuture) {
    Futures.addCallback(
        resultFuture.getResult(),
        new FutureCallback<BuildResult>() {
          @Override
          public void onSuccess(@Nullable BuildResult result) {
            Objects.requireNonNull(result);

            String fullyQualifiedName = result.getRule().getFullyQualifiedName();

            if (!result.isSuccess()) {
              LOG.error(String.format("Building of target [%s] failed.", fullyQualifiedName));
              // Ensure the build doesn't deadlock
              exitCode.set(ExitCode.BUILD_ERROR);
              return;
            } else {
              LOG.info(String.format("Building of target [%s] completed.", fullyQualifiedName));
            }

            buildTracker.recordFinishedTarget(result);
            registerUploadCompletionHandler(Objects.requireNonNull(result));
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.error(t, "Building of unknown target failed.");
            // Fail the Stampede build, and ensure it doesn't deadlock.
            exitCode.set(ExitCode.BUILD_ERROR);
          }
        },
        MoreExecutors.directExecutor());
  }

  private void registerUploadCompletionHandler(BuildResult buildResult) {
    String fullyQualifiedName = buildResult.getRule().getFullyQualifiedName();
    Futures.addCallback(
        buildResult.getUploadCompleteFuture().orElse(Futures.immediateFuture(null)),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(@Nullable Void result) {
            buildTracker.recordUploadedTarget(fullyQualifiedName);
          }

          @Override
          public void onFailure(Throwable t) {
            // TODO(alisdair,ruibm,msienkiewicz): We used to have async upload confirmations from
            // cache which made this codepath (almost) never get triggered - we would crash the
            // build if it happened. We need to now look at error rate and decide on a retry/crash
            // policy. Until then, log and progress as if upload was successful.
            registerFailedUploadHandler(t, buildResult.getRule(), fullyQualifiedName);
            buildTracker.recordUploadedTarget(fullyQualifiedName);
          }
        },
        MoreExecutors.directExecutor());
  }

  private void registerFailedUploadHandler(
      Throwable uploadThrowable, BuildRule buildRule, String fullyQualifiedName) {
    Futures.addCallback(
        Objects.requireNonNull(buildExecutor)
            .getCachingBuildEngine()
            .getRuleKeyCalculator()
            .calculate(eventBus, buildRule),
        new FutureCallback<RuleKey>() {
          @Override
          public void onSuccess(RuleKey ruleKey) {
            LOG.error(
                uploadThrowable,
                String.format(
                    "Cache upload failed for target [%s] with rulekey [%s].",
                    fullyQualifiedName, ruleKey));
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.error(
                t,
                String.format(
                    "Cache upload failed for target [%s] with unknown rulekey (calculation failed).",
                    fullyQualifiedName));
          }
        },
        // Rulekey should have already been computed so direct executor is fine.
        MoreExecutors.directExecutor());
  }

  private void handleThriftException(ThriftException e) throws IOException {
    if (buildCompletionChecker.hasBuildFinished()) {
      // If the build has finished and this minion was not doing anything and was just
      // waiting for work, just exit gracefully with return code 0.
      LOG.warn(
          e,
          ("Minion failed to connect to coordinator, "
              + "but build already finished, so shutting down."));
      finished.set(true);
      return;
    } else {
      throw e;
    }
  }
}
