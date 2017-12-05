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

import static com.facebook.buck.distributed.build_slave.BuildTargetsQueueTest.CHAIN_TOP_TARGET;
import static com.facebook.buck.distributed.build_slave.BuildTargetsQueueTest.LEAF_TARGET;
import static com.facebook.buck.distributed.build_slave.BuildTargetsQueueTest.LEFT_TARGET;
import static com.facebook.buck.distributed.build_slave.BuildTargetsQueueTest.RIGHT_TARGET;
import static com.facebook.buck.distributed.build_slave.BuildTargetsQueueTest.ROOT_TARGET;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.slb.ThriftException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MinionModeRunnerIntegrationTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;
  private static final int MAX_PARALLEL_WORK_UNITS = 10;
  private static final long POLL_LOOP_INTERVAL_MILLIS = 9;
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000;

  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  @Test(expected = ThriftException.class)
  public void testMinionWithoutServerAndWithUnfinishedBuild()
      throws IOException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> false;
    FakeBuildExecutorImpl localBuilder = new FakeBuildExecutorImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            OptionalInt.of(4242),
            Futures.immediateFuture(localBuilder),
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl1"),
            MAX_PARALLEL_WORK_UNITS,
            checker,
            POLL_LOOP_INTERVAL_MILLIS,
            new NoOpUnexpectedSlaveCacheMissTracker(),
            CONNECTION_TIMEOUT_MILLIS);

    minion.runAndReturnExitCode(createFakeHeartbeatService());
    Assert.fail("The previous line should've thrown an exception.");
  }

  /** Returns a mock HeartbeatService that will always return valid Closeables. */
  public static HeartbeatService createFakeHeartbeatService() {
    HeartbeatService service = EasyMock.createNiceMock(HeartbeatService.class);
    EasyMock.expect(service.addCallback(EasyMock.anyString(), EasyMock.anyObject()))
        .andReturn(
            new Closeable() {
              @Override
              public void close() throws IOException {}
            })
        .anyTimes();
    EasyMock.replay(service);
    return service;
  }

  @Test
  public void testMinionWithoutServerAndWithFinishedBuild()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> true;
    FakeBuildExecutorImpl localBuilder = new FakeBuildExecutorImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            OptionalInt.of(4242),
            Futures.immediateFuture(localBuilder),
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl2"),
            MAX_PARALLEL_WORK_UNITS,
            checker,
            POLL_LOOP_INTERVAL_MILLIS,
            new NoOpUnexpectedSlaveCacheMissTracker(),
            CONNECTION_TIMEOUT_MILLIS);

    int exitCode = minion.runAndReturnExitCode(createFakeHeartbeatService());
    // Server does not exit because the build has already been marked as finished.
    Assert.assertEquals(0, exitCode);
  }

  @Test
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    runDiamondGraphWithChain(createFakeHeartbeatService());
  }

  @Test
  public void testMinionReportsAliveToCoordinator()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    Closeable closeable = EasyMock.createMock(Closeable.class);
    closeable.close();
    EasyMock.expectLastCall().once();
    HeartbeatService service = EasyMock.createMock(HeartbeatService.class);
    EasyMock.expect(service.addCallback(EasyMock.anyString(), EasyMock.notNull()))
        .andReturn(closeable)
        .once();
    EasyMock.replay(service, closeable);
    runDiamondGraphWithChain(service);
    EasyMock.verify(service, closeable);
  }

  private BuildResult.Builder successfulBuildResult(String target) {
    return BuildResult.builder()
        .setRule(new FakeBuildRule(target))
        .setStatus(BuildRuleStatus.SUCCESS)
        .setSuccessOptional(BuildRuleSuccessType.BUILT_LOCALLY)
        .setCacheResult(CacheResult.miss());
  }

  @Test
  public void testUnexpectedCacheMissesAreRecorded()
      throws NoSuchBuildTargetException, InterruptedException, IOException {
    // Graph structure:
    //                      +-- (miss target 2)
    //                      | /     |
    //         +--- right <-+--+    |
    //         |           /   |    v
    // root <--+          /    +-- chain top <-- leaf <-- (miss target 1)
    //         |         v     |
    //         +---- left <----+
    //         |
    //         +-- (miss target 3)

    final String MISS_TARGET_1 = ROOT_TARGET + "_miss1";
    final String MISS_TARGET_2 = ROOT_TARGET + "_miss2";
    final String MISS_TARGET_3 = ROOT_TARGET + "_miss3";

    UnexpectedSlaveCacheMissTracker unexpectedCacheMissTracker =
        EasyMock.createMock(UnexpectedSlaveCacheMissTracker.class);
    BuildExecutor buildExecutor = EasyMock.createMock(BuildExecutor.class);

    EasyMock.expect(
            buildExecutor.waitForBuildToFinish(
                EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
        .andReturn(0)
        .anyTimes();

    EasyMock.expect(buildExecutor.initializeBuild(EasyMock.anyObject()))
        .andAnswer(
            () -> {
              Iterable<String> targets = (Iterable<String>) EasyMock.getCurrentArguments()[0];

              ArrayList<BuildEngineResult> results = new ArrayList<>();
              for (String target : targets) {
                BuildResult.Builder buildResult = successfulBuildResult(target);
                switch (target) {
                  case ROOT_TARGET:
                    buildResult.setDepsWithCacheMisses(
                        ImmutableSet.of(RIGHT_TARGET, LEFT_TARGET, MISS_TARGET_3));
                    break;
                  case RIGHT_TARGET:
                    buildResult.setDepsWithCacheMisses(
                        ImmutableSet.of(CHAIN_TOP_TARGET, MISS_TARGET_2));
                    break;
                  case LEFT_TARGET:
                    buildResult.setDepsWithCacheMisses(
                        ImmutableSet.of(CHAIN_TOP_TARGET, MISS_TARGET_2));
                    break;
                  case CHAIN_TOP_TARGET:
                    buildResult.setDepsWithCacheMisses(ImmutableSet.of(LEAF_TARGET, MISS_TARGET_2));
                    break;
                  case LEAF_TARGET:
                    buildResult.setDepsWithCacheMisses(ImmutableSet.of(MISS_TARGET_1));
                    break;
                }
                results.add(
                    BuildEngineResult.builder()
                        .setResult(Futures.immediateFuture(buildResult.build()))
                        .build());
              }
              return results;
            })
        .times(3);

    buildExecutor.shutdown();
    EasyMock.expectLastCall().once();

    Capture<Integer> cacheMissCounts = EasyMock.newCapture(CaptureType.ALL);
    unexpectedCacheMissTracker.onUnexpectedCacheMiss(EasyMock.captureInt(cacheMissCounts));
    EasyMock.expectLastCall().atLeastOnce();

    EasyMock.replay(buildExecutor);
    EasyMock.replay(unexpectedCacheMissTracker);

    runDiamondGraphWithChain(
        createFakeHeartbeatService(), buildExecutor, unexpectedCacheMissTracker);

    EasyMock.verify(buildExecutor);
    EasyMock.verify(unexpectedCacheMissTracker);

    Assert.assertEquals(3, cacheMissCounts.getValues().stream().mapToInt(Integer::intValue).sum());
  }

  private void runDiamondGraphWithChain(
      HeartbeatService service,
      BuildExecutor buildExecutor,
      UnexpectedSlaveCacheMissTracker unexpectedCacheMissTracker)
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> false;
    try (ThriftCoordinatorServer server = createServer()) {
      server.start();
      MinionModeRunner minion =
          new MinionModeRunner(
              "localhost",
              OptionalInt.of(server.getPort()),
              Futures.immediateFuture(buildExecutor),
              STAMPEDE_ID,
              new BuildSlaveRunId().setId("sl3"),
              MAX_PARALLEL_WORK_UNITS,
              checker,
              POLL_LOOP_INTERVAL_MILLIS,
              unexpectedCacheMissTracker,
              CONNECTION_TIMEOUT_MILLIS);
      int exitCode = minion.runAndReturnExitCode(service);
      Assert.assertEquals(0, exitCode);
    }
  }

  private void runDiamondGraphWithChain(HeartbeatService service)
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    FakeBuildExecutorImpl buildExecutor = new FakeBuildExecutorImpl();
    runDiamondGraphWithChain(service, buildExecutor, new NoOpUnexpectedSlaveCacheMissTracker());
    Assert.assertEquals(5, buildExecutor.getBuildTargets().size());
    Assert.assertEquals(ROOT_TARGET, buildExecutor.getBuildTargets().get(4));
  }

  private ThriftCoordinatorServer createServer() throws NoSuchBuildTargetException, IOException {
    BuildTargetsQueue queue = BuildTargetsQueueTest.createDiamondDependencyQueueWithChainFromLeaf();
    return ThriftCoordinatorServerIntegrationTest.createServerOnRandomPort(queue);
  }

  public static class FakeBuildExecutorImpl implements BuildExecutor {

    private final List<String> buildTargets;

    public FakeBuildExecutorImpl() {
      buildTargets = new ArrayList<>();
    }

    public List<String> getBuildTargets() {
      return buildTargets;
    }

    @Override
    public int buildLocallyAndReturnExitCode(
        Iterable<String> targetsToBuild, Optional<Path> pathToBuildReport)
        throws IOException, InterruptedException {
      buildTargets.addAll(ImmutableList.copyOf((targetsToBuild)));
      return 0;
    }

    @Override
    public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild)
        throws IOException {

      buildTargets.addAll(ImmutableList.copyOf((targetsToBuild)));

      List<BuildEngineResult> results = new ArrayList<>();
      for (String target : targetsToBuild) {
        BuildRule fakeBuildRule = new FakeBuildRule(target);
        BuildResult buildResult =
            BuildResult.success(
                fakeBuildRule,
                BuildRuleSuccessType.BUILT_LOCALLY,
                CacheResult.miss(),
                Futures.immediateFuture(null));

        BuildEngineResult buildEngineResult =
            BuildEngineResult.builder().setResult(Futures.immediateFuture(buildResult)).build();

        results.add(buildEngineResult);
      }

      return results;
    }

    @Override
    public int waitForBuildToFinish(
        Iterable<String> targetsToBuild,
        List<BuildEngineResult> resultFutures,
        Optional<Path> pathToBuildReport) {
      return 0;
    }

    @Override
    public CachingBuildEngine getCachingBuildEngine() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() throws IOException {
      // Nothing to cleanup in this implementation
    }
  }
}
