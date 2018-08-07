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

/*
 * Copyright 2018-present Facebook; Inc.
 *
 * Licensed under the Apache License; Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing; software
 * distributed under the License is distributed on an "AS IS" BASIS; WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND; either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.distributed.build_client;

import static org.easymock.EasyMock.anyBoolean;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizer;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistLocalBuildMode;
import com.facebook.buck.distributed.DistributedExitCode;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StampedeBuildClientTest {
  private static final long TEST_TIMEOUT_MILLIS = 2000;

  private static final StampedeId INITIALIZED_STAMPEDE_ID = createStampedeId("id_one");

  private static final int SUCCESS_CODE = 0;
  private static final boolean NO_FALLBACK = false;
  private static final boolean FALLBACK_ENABLED = true;
  private static final String SUCCESS_STATUS_MSG =
      "The build finished locally before distributed build finished.";

  private BuckEventBus mockEventBus;
  private RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer;
  private ExecutorService executorForLocalBuild;
  private ExecutorService executorForDistBuildController;
  private DistBuildService mockDistBuildService;
  private BuildEvent.DistBuildStarted distBuildStartedEvent;
  private CountDownLatch localBuildExecutorInvokerPhaseOneLatch;
  private CountDownLatch localBuildExecutorInvokerPhaseTwoLatch;
  private LocalBuildExecutorInvoker guardedLocalBuildExecutorInvoker;
  private LocalBuildExecutorInvoker mockLocalBuildExecutorInvoker;
  private CountDownLatch distBuildControllerInvokerLatch;
  private DistBuildControllerInvoker guardedDistBuildControllerInvoker;
  private DistBuildControllerInvoker mockDistBuildControllerInvoker;
  private StampedeBuildClient buildClient;
  private ExecutorService buildClientExecutor;
  private Build buildOneMock;
  private Build buildTwoMock;
  private CountDownLatch waitForRacingBuildCalledLatch;
  private CountDownLatch waitForSynchronizedBuildCalledLatch;
  private boolean waitGracefullyForDistributedBuildThreadToFinish;
  private long distributedBuildThreadKillTimeoutSeconds;
  private Optional<StampedeId> stampedeId = Optional.empty();

  @Before
  public void setUp() {
    mockEventBus = BuckEventBusForTests.newInstance();
    remoteBuildRuleSynchronizer = new RemoteBuildRuleSynchronizer();
    executorForLocalBuild = Executors.newSingleThreadExecutor();
    executorForDistBuildController = Executors.newSingleThreadExecutor();
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    distBuildStartedEvent = BuildEvent.distBuildStarted();
    mockLocalBuildExecutorInvoker = EasyMock.createMock(LocalBuildExecutorInvoker.class);
    localBuildExecutorInvokerPhaseOneLatch = new CountDownLatch(1);
    localBuildExecutorInvokerPhaseTwoLatch = new CountDownLatch(1);
    buildOneMock = EasyMock.createMock(Build.class);
    buildTwoMock = EasyMock.createMock(Build.class);
    AtomicBoolean isFirstBuild = new AtomicBoolean(true);
    guardedLocalBuildExecutorInvoker =
        new LocalBuildExecutorInvoker() {
          @Override
          public void initLocalBuild(
              boolean isDownloadHeavyBuild,
              RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {}

          @Override
          public ExitCode executeLocalBuild(
              boolean isDownloadHeavyBuild,
              RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
              CountDownLatch initializeBuildLatch,
              AtomicReference<Build> buildReference)
              throws IOException, InterruptedException {

            // First simulate initializing the build
            boolean wasFirstBuild = isFirstBuild.compareAndSet(true, false);
            if (wasFirstBuild) {
              buildReference.set(
                  buildOneMock); // Racing build, or synchronized build if single stage
            } else {
              buildReference.set(buildTwoMock); // Always synchronized build
            }
            initializeBuildLatch.countDown(); // Build reference has been set

            // Now wait for test to signal that the mock should be invoked and return an exit code.
            if (wasFirstBuild) {
              assertTrue(
                  localBuildExecutorInvokerPhaseOneLatch.await(
                      TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            } else {
              assertTrue(
                  localBuildExecutorInvokerPhaseTwoLatch.await(
                      TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            }

            return mockLocalBuildExecutorInvoker.executeLocalBuild(
                isDownloadHeavyBuild,
                remoteBuildRuleCompletionWaiter,
                initializeBuildLatch,
                buildReference);
          }
        };
    mockDistBuildControllerInvoker = EasyMock.createMock(DistBuildControllerInvoker.class);
    distBuildControllerInvokerLatch = new CountDownLatch(1);
    guardedDistBuildControllerInvoker =
        () -> {
          assertTrue(
              distBuildControllerInvokerLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
          return mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode();
        };
    waitForRacingBuildCalledLatch = new CountDownLatch(1);
    waitForSynchronizedBuildCalledLatch = new CountDownLatch(1);
    waitGracefullyForDistributedBuildThreadToFinish = false;
    distributedBuildThreadKillTimeoutSeconds = 1;
    createStampedBuildClient();
    buildClientExecutor = Executors.newSingleThreadExecutor();
  }

  private void createStampedBuildClient(StampedeId stampedeId) {
    this.stampedeId = Optional.of(stampedeId);
    createStampedBuildClient();
  }

  private void createStampedBuildClient() {
    this.buildClient =
        new StampedeBuildClient(
            mockEventBus,
            remoteBuildRuleSynchronizer,
            executorForLocalBuild,
            executorForDistBuildController,
            mockDistBuildService,
            distBuildStartedEvent,
            waitForRacingBuildCalledLatch,
            waitForSynchronizedBuildCalledLatch,
            guardedLocalBuildExecutorInvoker,
            guardedDistBuildControllerInvoker,
            new ClientStatsTracker("", ""),
            waitGracefullyForDistributedBuildThreadToFinish,
            distributedBuildThreadKillTimeoutSeconds,
            stampedeId);
  }

  @Test
  public void synchronizedBuildCompletesAfterDistBuildFailsForSinglePhaseBuildWithFallback()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // One phase. Fallback enabled. Racing build is skipped.
    // Distributed build fails, and then falls back to synchronized local build which completes

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    // Simulate failure at a remote minion
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(
            StampedeExecutionResult.of(DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE));

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.WAIT_FOR_REMOTE, FALLBACK_ENABLED));

    // Simulate most build rules finished event being received.
    distBuildControllerInvokerLatch.countDown(); // distributed build fails
    // ensure waitUntilFinished(..) called on build
    assertTrue(
        waitForSynchronizedBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseOneLatch.countDown(); // allow synchronized build to complete

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.SUCCESS,
        DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);
    verifyAllMocks();
  }

  @Test
  public void synchronizedBuildCompletesAfterDistBuildCompletesForSinglePhase()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // One phase. Fallback enabled. Racing build is skipped.
    // Distributed build completes, and then synchronized local build completes

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(StampedeExecutionResult.of(DistributedExitCode.SUCCESSFUL));

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.WAIT_FOR_REMOTE, FALLBACK_ENABLED));

    // Simulate most build rules finished event being received.
    distBuildControllerInvokerLatch.countDown(); // distributed build succeeds
    // ensure waitUntilFinished(..) called on build
    assertTrue(
        waitForSynchronizedBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseOneLatch.countDown(); // allow synchronized build to complete

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.SUCCESSFUL);
    verifyAllMocks();
  }

  @Test
  public void
      racerBuildKilledWhenMostBuildRulesFinishedThenFallsBackToSynchronizedBuildWhenDistBuildFails()
          throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases enabled. Fallback enabled.
    // During local racing build phase a 'most build rules finished' event is received.
    // Racing build is cancelled, and build moves to local synchronized phase.
    // Distributed build fails and then falls back to local synchronized build.

    // Racing build should be cancelled when most build rules finished event received
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    // Simulate failure at a remote minion
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(
            StampedeExecutionResult.of(DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE));

    // Ensure local synchronized build is invoked and finishes with failure code (as it was
    // terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, FALLBACK_ENABLED));

    // Simulate most build rules finished event being received.
    remoteBuildRuleSynchronizer.signalMostBuildRulesFinished(true);
    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    distBuildControllerInvokerLatch.countDown(); // distributed build fails
    // waitUntilFinished(..) called on sync build
    assertTrue(
        waitForSynchronizedBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseTwoLatch.countDown(); // allow synchronized build to complete

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.SUCCESS,
        DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);
    verifyAllMocks();
  }

  @Test
  public void
      racerBuildKilledWhenMostBuildRulesFinishedThenSynchronizedBuildKilledWhenDistBuildFails()
          throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases enabled. No fallback.
    // During local racing build phase a 'most build rules finished' event is received.
    // Racing build is cancelled, and build moves to local synchronized phase.
    // Distributed build fails and so local synchronized build is killed as no fallback.

    // Racing build should be cancelled when most build rules finished event received
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    // Simulate failure at a remote minion
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(
            StampedeExecutionResult.of(DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE));

    // Synchronized build should be cancelled when distributed build fails
    ensureTerminationOfBuild(buildTwoMock, localBuildExecutorInvokerPhaseTwoLatch);

    // Ensure local synchronized build is invoked and finishes with failure code (as it was
    // terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, NO_FALLBACK));

    // Simulate most build rules finished event being received.
    remoteBuildRuleSynchronizer.signalMostBuildRulesFinished(true);
    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    distBuildControllerInvokerLatch.countDown(); // distributed build fails
    // waitUntilFinished(..) called on sync build
    assertTrue(
        waitForSynchronizedBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.FATAL_GENERIC,
        DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);
    verifyAllMocks();
  }

  @Test
  public void
      racerBuildKilledWhenMostBuildRulesFinishedThenDistBuildKilledWhenSynchronizedBuildWins()
          throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases enabled. No fallback.
    // During local racing build phase a 'most build rules finished' event is received.
    // Racing build is cancelled, and build moves to local synchronized phase.
    // Synchronized build wins and then distributed build is killed.
    createStampedBuildClient(INITIALIZED_STAMPEDE_ID);

    // Racing build should be cancelled when most build rules finished event received
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    // Note: we always wait for racing build thread to shut down cleanly.
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    // Distributed build job should be set to finished when synchronized build completes
    mockDistBuildService.setFinalBuildStatus(
        INITIALIZED_STAMPEDE_ID, BuildStatus.FINISHED_SUCCESSFULLY, SUCCESS_STATUS_MSG);
    EasyMock.expectLastCall();

    // Synchronized build returns with success code
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, NO_FALLBACK));

    // Simulate most build rules finished event being received.
    remoteBuildRuleSynchronizer.signalMostBuildRulesFinished(true);
    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseTwoLatch.countDown(); // Local synchronized build completes

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.LOCAL_BUILD_FINISHED_FIRST);
    verifyAllMocks();
  }

  @Test
  public void
      racerBuildKilledWhenMostBuildRulesFinishedThenWaitsForSynchronizedBuildWhenDistBuildCompletes()
          throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases enabled. No fallback. 'Most build rules finished' event received during racing.
    // Racing build is cancelled, and build moves to local synchronized phase.
    // Distributed build finishes, and then waits for local build to finish.

    // Racing build should be cancelled when most build rules finished event received
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    // Distributed build finishes successfully
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(StampedeExecutionResult.of(DistributedExitCode.SUCCESSFUL));

    // Synchronized build returns with success code
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, NO_FALLBACK));

    // Simulate most build rules finished event being received.
    remoteBuildRuleSynchronizer.signalMostBuildRulesFinished(true);

    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    distBuildControllerInvokerLatch.countDown(); // Distributed build completes
    // waitUntilFinished(..) called on sync build
    assertTrue(
        waitForSynchronizedBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseTwoLatch.countDown(); // Local synchronized build completes

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.SUCCESSFUL);
    verifyAllMocks();
  }

  @Test
  public void racerBuildWinsAndThenDistributedBuildKilled()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases enabled. No fallback. Local racing build finishes before anything else.
    // Distributed build is still pending, so it's killed, and racing build exit code returned.
    createStampedBuildClient(INITIALIZED_STAMPEDE_ID);

    // Ensure local racing build is invoked and finishes with success code
    localBuildExecutorInvokerPhaseOneLatch.countDown();
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    mockDistBuildService.setFinalBuildStatus(
        INITIALIZED_STAMPEDE_ID, BuildStatus.FINISHED_SUCCESSFULLY, SUCCESS_STATUS_MSG);
    EasyMock.expectLastCall();

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.WAIT_FOR_REMOTE, NO_FALLBACK));

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.LOCAL_BUILD_FINISHED_FIRST);
    verifyAllMocks();
  }

  @Test
  public void fireAndForgetBuildSchedulesRemoteAndExits()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // One phase: No fallback. Local build schedules remote build and exits.
    createStampedBuildClient(INITIALIZED_STAMPEDE_ID);

    distBuildControllerInvokerLatch.countDown();
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(StampedeExecutionResult.of(DistributedExitCode.SUCCESSFUL));

    EasyMock.expectLastCall();
    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.FIRE_AND_FORGET, FALLBACK_ENABLED));

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.SUCCESSFUL);
    verifyAllMocks();
  }

  @Test
  public void remoteBuildFailsAndThenRacingBuildKilledAsNoLocalFallback()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases. No fallback. Remote build fails during racing build phase.
    // Local fallback *is not* enabled, so build client kills the racing build and returns.

    // Simulate failure at a remote minion
    distBuildControllerInvokerLatch.countDown();
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(
            StampedeExecutionResult.of(DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE));

    // Ensure Build object for racing build is terminated and then unlock local build executor
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.WAIT_FOR_REMOTE, NO_FALLBACK));

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.FATAL_GENERIC,
        DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);
    verifyAllMocks();
  }

  @Test
  public void remoteBuildFailsAndThenWaitsForRacingBuildToCompleteAsFallbackEnabled()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Remote build fails during racing build phase, before racing build has finished.
    // Local fallback *is* enabled, so racing build keeps going until completion.

    // Simulate failure at a remote minion
    distBuildControllerInvokerLatch.countDown();
    expect(mockDistBuildControllerInvoker.runDistBuildAndReturnExitCode())
        .andReturn(
            StampedeExecutionResult.of(DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE));

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, FALLBACK_ENABLED));

    // Only let local build runner finish once we are sure distributed build thread is dead,
    // and waitUntilFinished(..) called on local racing build runner
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseOneLatch.countDown();

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.SUCCESS,
        DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);
    verifyAllMocks();
  }

  @Test
  public void mostBuildRulesFailAndThenRacingBuildKilledAsNoLocalFallback()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases. No fallback.
    // Remote build fails during racing build phase, but exit code has not been set yet.
    // Most rules callback triggers the local build latch.
    // Local fallback *is not* enabled, so build client kills the racing build and returns.

    // Ensure Build object for racing build is terminated and then unlock local build executor
    ensureTerminationOfBuild(buildOneMock, localBuildExecutorInvokerPhaseOneLatch);

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.FATAL_GENERIC);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, NO_FALLBACK));

    // Simulate most build rules finished being failed.
    remoteBuildRuleSynchronizer.signalCompletionOfRemoteBuild(false);
    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

    assertLocalAndDistributedExitCodes(
        buildClientFuture,
        ExitCode.FATAL_GENERIC,
        DistributedExitCode.DISTRIBUTED_PENDING_EXIT_CODE);
    verifyAllMocks();
  }

  @Test
  public void mostBuildRulesFailAndThenWaitsForRacingBuildToCompleteAsFallbackEnabled()
      throws InterruptedException, IOException, ExecutionException {
    // Summary:
    // Two phases. Fallback enabled.
    // Remote build fails during racing build phase, but exit code has not been set yet.
    // Most rules callback triggers the local build latch.
    // Local fallback *is* enabled, so racing build keeps going until completion.

    // Ensure local racing build is invoked and finishes with failure code (as it was terminated)
    expectMockLocalBuildExecutorReturnsWithCode(ExitCode.SUCCESS);

    replayAllMocks();

    // Run the build client in another thread
    Future<Optional<ExitCode>> buildClientFuture =
        buildClientExecutor.submit(
            () -> buildClient.build(DistLocalBuildMode.NO_WAIT_FOR_REMOTE, FALLBACK_ENABLED));

    // Simulate most build rules finished being failed.
    remoteBuildRuleSynchronizer.signalCompletionOfRemoteBuild(false);
    // waitUntilFinished(..) called on racing build
    assertTrue(waitForRacingBuildCalledLatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    localBuildExecutorInvokerPhaseOneLatch.countDown();

    assertLocalAndDistributedExitCodes(
        buildClientFuture, ExitCode.SUCCESS, DistributedExitCode.DISTRIBUTED_PENDING_EXIT_CODE);
    verifyAllMocks();
  }

  private static StampedeId createStampedeId(String stampedeIdString) {
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(stampedeIdString);
    return stampedeId;
  }

  private void expectMockLocalBuildExecutorReturnsWithCode(ExitCode code)
      throws IOException, InterruptedException {
    expect(
            mockLocalBuildExecutorInvoker.executeLocalBuild(
                anyBoolean(),
                anyObject(RemoteBuildRuleSynchronizer.class),
                anyObject(CountDownLatch.class),
                anyObject(AtomicReference.class)))
        .andReturn(code);
  }

  private void ensureTerminationOfBuild(Build buildMock, CountDownLatch buildPhaseLatch) {
    buildMock.terminateBuildWithFailure(anyObject(Throwable.class));
    EasyMock.expectLastCall()
        .andAnswer(
            () -> {
              // Build has terminated, so enable invoker to be called and return exit code.
              buildPhaseLatch.countDown();
              return null;
            });
  }

  private void assertLocalAndDistributedExitCodes(
      Future<Optional<ExitCode>> result,
      ExitCode expectedLocalExitCode,
      DistributedExitCode expectedDistExitCode)
      throws ExecutionException, InterruptedException {
    Optional<ExitCode> localBuildExitCode = Optional.empty();
    try {
      localBuildExitCode = result.get(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      Assert.fail("Test timed out.");
    }

    assertTrue(localBuildExitCode.isPresent());
    assertEquals(expectedLocalExitCode, localBuildExitCode.get());
    assertEquals(expectedDistExitCode, buildClient.getDistBuildExitCode());
  }

  private void replayAllMocks() {
    replay(mockLocalBuildExecutorInvoker, mockDistBuildControllerInvoker);
    replay(buildOneMock, buildTwoMock); // No calls expected on buildTwoMock
    replay(mockDistBuildService);
  }

  private void verifyAllMocks() {
    verify(mockLocalBuildExecutorInvoker, mockDistBuildControllerInvoker);
    verify(buildOneMock, buildTwoMock);
    verify(mockDistBuildService);
  }
}
