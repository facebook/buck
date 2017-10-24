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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class MinionModeRunner implements DistBuildModeRunner {
  private static final Logger LOG = Logger.get(MinionModeRunner.class);

  // TODO(alisdair): make this a .buckconfig setting
  private static final int IDLE_SLEEP_INTERVAL_MS = 10;

  private final String coordinatorAddress;
  private final int coordinatorPort;
  private final LocalBuilder builder;
  private final StampedeId stampedeId;

  // I.e. the number of CPU cores that are currently free at this minion.
  // Each work unit takes up one core.
  private final AtomicInteger availableWorkUnitBuildCapacity;
  private final BuildCompletionChecker buildCompletionChecker;
  private final ExecutorService buildExecutorService;

  // All nodes that have finished build (and been uploaded) that need to be signalled
  // back to the coordinator.
  private final Set<String> finishedTargetsToSignal = new HashSet<>();

  // These are the targets at the end of work unit, when complete the corresponding core is free.
  private final Set<String> workUnitTerminalTargets = new HashSet<>();

  // Signals to the main loop that it can stop requesting new work.
  private final AtomicBoolean finished = new AtomicBoolean(false);

  // Aggregate exit code for the minion. Non-zero if any set of build targets failed.
  private AtomicInteger exitCode = new AtomicInteger(0);

  /** Callback when the build has completed. */
  public interface BuildCompletionChecker {
    boolean hasBuildFinished() throws IOException;
  }

  /** Encapsulates a Thrift call */
  @FunctionalInterface
  public interface ThriftCall {
    void apply() throws IOException;
  }

  public MinionModeRunner(
      String coordinatorAddress,
      int coordinatorPort,
      LocalBuilder builder,
      StampedeId stampedeId,
      int availableWorkUnitBuildCapacity,
      BuildCompletionChecker buildCompletionChecker) {
    this(
        coordinatorAddress,
        coordinatorPort,
        builder,
        stampedeId,
        availableWorkUnitBuildCapacity,
        buildCompletionChecker,
        MostExecutors.newMultiThreadExecutor(
            new CommandThreadFactory("MinionBuilderThread"), availableWorkUnitBuildCapacity));
  }

  @VisibleForTesting
  public MinionModeRunner(
      String coordinatorAddress,
      int coordinatorPort,
      LocalBuilder builder,
      StampedeId stampedeId,
      int availableWorkUnitBuildCapacity,
      BuildCompletionChecker buildCompletionChecker,
      ExecutorService buildExecutorService) {
    this.builder = builder;
    this.stampedeId = stampedeId;
    Preconditions.checkArgument(
        coordinatorPort > 0, "The coordinator's port needs to be a positive integer.");
    this.coordinatorAddress = coordinatorAddress;
    this.coordinatorPort = coordinatorPort;
    this.availableWorkUnitBuildCapacity = new AtomicInteger(availableWorkUnitBuildCapacity);

    this.buildCompletionChecker = buildCompletionChecker;

    this.buildExecutorService = buildExecutorService;

    LOG.info(
        String.format(
            "Started new minion that can build [%d] work units in parallel",
            availableWorkUnitBuildCapacity));
  }

  @Override
  public int runAndReturnExitCode() throws IOException, InterruptedException {
    try (ThriftCoordinatorClient client =
        new ThriftCoordinatorClient(coordinatorAddress, coordinatorPort, stampedeId)) {
      completionCheckingThriftCall(() -> client.start());

      final String minionId = generateNewMinionId();

      while (!finished.get()) {
        signalFinishedTargetsAndFetchMoreWork(minionId, client);
        Thread.sleep(IDLE_SLEEP_INTERVAL_MS);
      }

      completionCheckingThriftCall(() -> client.stop());
    }

    // At this point there is no more work to schedule, so wait for the build to finish.
    buildExecutorService.shutdown();
    buildExecutorService.awaitTermination(30, TimeUnit.MINUTES);

    builder.shutdown();

    return exitCode.get();
  }

  private void signalFinishedTargetsAndFetchMoreWork(
      String minionId, ThriftCoordinatorClient client) throws IOException, InterruptedException {
    List<String> targetsToSignal = getLatestFinishedTargetsToSignal();

    if (availableWorkUnitBuildCapacity.get() == 0
        && exitCode.get() == 0
        && targetsToSignal.size() == 0) {
      return; // Making a request will not move the build forward, so wait a while and try again.
    }

    final List<WorkUnit> workUnitsToBuild = Lists.newArrayList();
    LOG.info(
        String.format(
            "Minion [%s] fetching work. Signalling [%d] finished targets",
            minionId, targetsToSignal.size()));

    try {
      GetWorkResponse response =
          client.getWork(
              minionId, exitCode.get(), targetsToSignal, availableWorkUnitBuildCapacity.get());
      if (!response.isContinueBuilding()) {
        LOG.info(String.format("Minion [%s] told to stop building.", minionId));
        finished.set(true);
      }

      workUnitsToBuild.addAll(response.getWorkUnits());
    } catch (ThriftException ex) {
      handleThriftException(ex);
      return;
    }

    if (workUnitsToBuild.size() == 0) {
      return; // Nothing new to build
    }

    // Each fetched work unit is going to occupy one core, mark the core as busy until the
    // work unit has finished.
    availableWorkUnitBuildCapacity.addAndGet(Math.negateExact(workUnitsToBuild.size()));

    buildExecutorService.execute(
        () -> {
          try {
            performBuildOfWorkUnits(minionId, workUnitsToBuild);
          } catch (IOException e) {
            LOG.error(e, "Failed whilst building targets. Terminating build. ");
            exitCode.set(-1);
            finished.set(true);
          }
        });
  }

  private List<String> getLatestFinishedTargetsToSignal() {
    // Make a copy of the finished targets set
    synchronized (finishedTargetsToSignal) {
      List<String> targets = Lists.newArrayList(finishedTargetsToSignal);
      finishedTargetsToSignal.clear();
      return targets;
    }
  }

  private void performBuildOfWorkUnits(String minionId, List<WorkUnit> workUnitsToBuild)
      throws IOException {
    // Each work unit consists of one of more build targets. Aggregate them all together
    // and feed them to the build engine as a batch.
    List<String> targetsToBuild = Lists.newArrayList();
    for (WorkUnit workUnit : workUnitsToBuild) {
      List<String> buildTargetsInWorkUnit = workUnit.getBuildTargets();
      Preconditions.checkArgument(buildTargetsInWorkUnit.size() > 0);

      targetsToBuild.addAll(buildTargetsInWorkUnit);
      recordTerminalTarget(buildTargetsInWorkUnit);
    }

    LOG.info(
        String.format(
            "Minion [%s] is about to build [%d] targets as part of [%d] work units",
            minionId, targetsToBuild.size(), workUnitsToBuild.size()));

    LOG.debug(String.format("Targets: [%s]", Joiner.on(", ").join(targetsToBuild)));

    // Start the build, and get futures representing the results.
    List<BuildEngineResult> resultFutures = builder.initializeBuild(targetsToBuild);

    // Register handlers that will ensure we free up cores as soon as a work unit is complete,
    // and signal built targets as soon as they are uploaded to the cache.
    for (BuildEngineResult resultFuture : resultFutures) {
      registerBuildRuleCompletionHandler(resultFuture);
    }

    // Wait for the targets to finish building and get the exit code.
    int lastExitCode = builder.waitForBuildToFinish(targetsToBuild, resultFutures);

    LOG.info(String.format("Minion [%s] finished with exit code [%d].", minionId, lastExitCode));

    if (lastExitCode != 0) {
      exitCode.set(lastExitCode);
    }
  }

  // Keep a record of the last target in a work unit, as we need to wait for this to finish
  // before the corresponding core can be freed.
  private void recordTerminalTarget(List<String> buildTargetsInWorkUnit) {
    synchronized (workUnitTerminalTargets) {
      workUnitTerminalTargets.add(buildTargetsInWorkUnit.get(buildTargetsInWorkUnit.size() - 1));
    }
  }

  private void registerBuildRuleCompletionHandler(BuildEngineResult resultFuture) {
    Futures.addCallback(
        resultFuture.getResult(),
        new FutureCallback<BuildResult>() {
          @Override
          public void onSuccess(@Nullable BuildResult result) {
            Preconditions.checkNotNull(result);

            final String fullyQualifiedName = result.getRule().getFullyQualifiedName();

            if (result.getSuccess() == null) {
              LOG.error(String.format("Building of target [%s] failed.", fullyQualifiedName));
              exitCode.set(1); // Ensure the build doesn't deadlock
              return;
            } else {
              LOG.info(String.format("Building of target [%s] completed.", fullyQualifiedName));
            }

            attemptToFreeUpCore(fullyQualifiedName);
            registerUploadCompletionHandler(Preconditions.checkNotNull(result));
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.error(t, String.format("Building of unknown target failed."));
            exitCode.set(1); // Fail the Stampede build, and ensure it doesn't deadlock.
          }
        });
  }

  // If a target that just finished was the terminal node in a work unit, then that core
  // is now available for further work.
  private void attemptToFreeUpCore(String finishedTarget) {
    synchronized (workUnitTerminalTargets) {
      if (!workUnitTerminalTargets.contains(finishedTarget)) {
        return;
      }

      availableWorkUnitBuildCapacity.incrementAndGet();
      workUnitTerminalTargets.remove(finishedTarget);
    }
  }

  private void registerUploadCompletionHandler(final BuildResult buildResult) {
    final String fullyQualifiedName = buildResult.getRule().getFullyQualifiedName();
    Futures.addCallback(
        buildResult.getUploadCompleteFuture(),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(@Nullable Void result) {
            recordUploadedTarget(fullyQualifiedName);
          }

          @Override
          public void onFailure(Throwable t) {
            // TODO(alisdair,ruibm): re-try this, maybe on a different minion.
            LOG.error(t, String.format("Cache upload failed for target %s", fullyQualifiedName));
            exitCode.set(1); // Fail the Stampede build, and ensure it doesn't deadlock.
          }
        });
  }

  // Once a target has been built and uploaded to the cache, it is now safe to signal
  // to the coordinator that the target is finished.
  private void recordUploadedTarget(String target) {
    synchronized (finishedTargetsToSignal) {
      finishedTargetsToSignal.add(target);
    }
  }

  private void completionCheckingThriftCall(ThriftCall thriftCall) throws IOException {
    try {
      thriftCall.apply();
    } catch (ThriftException e) {
      handleThriftException(e);
      return;
    }
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

  private static String generateNewMinionId() {
    String hostname = "Unknown";
    try {
      InetAddress addr;
      addr = InetAddress.getLocalHost();
      hostname = addr.getHostName();
    } catch (UnknownHostException ex) {
      System.out.println("Hostname can not be resolved");
    }

    return String.format("minion:%s:%d", hostname, new Random().nextInt(Integer.MAX_VALUE));
  }
}
