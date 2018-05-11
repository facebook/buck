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

import static com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET;
import static com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory.LEAF_TARGET;
import static com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory.LEFT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory.RIGHT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory.ROOT_TARGET;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.timing.FakeClock;
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
  private static final MinionType MINION_TYPE = MinionType.STANDARD_SPEC;
  private static final int MAX_PARALLEL_WORK_UNITS = 10;
  private static final long POLL_LOOP_INTERVAL_MILLIS = 9;
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000;
  private static final BuckEventBus BUCK_EVENT_BUS =
      new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId());

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
            MINION_TYPE,
            new BuildSlaveRunId().setId("sl1"),
            new SingleBuildCapacityTracker(MAX_PARALLEL_WORK_UNITS),
            checker,
            POLL_LOOP_INTERVAL_MILLIS,
            new NoOpMinionBuildProgressTracker(),
            CONNECTION_TIMEOUT_MILLIS,
            BUCK_EVENT_BUS);

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
              public void close() {}
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
            MINION_TYPE,
            new BuildSlaveRunId().setId("sl2"),
            new SingleBuildCapacityTracker(MAX_PARALLEL_WORK_UNITS),
            checker,
            POLL_LOOP_INTERVAL_MILLIS,
            new NoOpMinionBuildProgressTracker(),
            CONNECTION_TIMEOUT_MILLIS,
            BUCK_EVENT_BUS);

    ExitCode exitCode = minion.runAndReturnExitCode(createFakeHeartbeatService());
    // Server does not exit because the build has already been marked as finished.
    Assert.assertEquals(ExitCode.SUCCESS, exitCode);
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

    String MISS_TARGET_1 = ROOT_TARGET + "_miss1";
    String MISS_TARGET_2 = ROOT_TARGET + "_miss2";
    String MISS_TARGET_3 = ROOT_TARGET + "_miss3";

    MinionBuildProgressTracker unexpectedCacheMissTracker =
        EasyMock.createMock(MinionBuildProgressTracker.class);
    BuildExecutor buildExecutor = EasyMock.createMock(BuildExecutor.class);

    EasyMock.expect(
            buildExecutor.waitForBuildToFinish(
                EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
        .andReturn(ExitCode.SUCCESS)
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

    unexpectedCacheMissTracker.updateTotalRuleCount(EasyMock.anyInt());
    EasyMock.expectLastCall().anyTimes();

    unexpectedCacheMissTracker.updateFinishedRuleCount(EasyMock.anyInt());
    EasyMock.expectLastCall().anyTimes();

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
      MinionBuildProgressTracker unexpectedCacheMissTracker)
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
              MINION_TYPE,
              new BuildSlaveRunId().setId("sl3"),
              new SingleBuildCapacityTracker(MAX_PARALLEL_WORK_UNITS),
              checker,
              POLL_LOOP_INTERVAL_MILLIS,
              unexpectedCacheMissTracker,
              CONNECTION_TIMEOUT_MILLIS,
              BUCK_EVENT_BUS);
      ExitCode exitCode = minion.runAndReturnExitCode(service);
      Assert.assertEquals(ExitCode.SUCCESS, exitCode);
    }
  }

  private void runDiamondGraphWithChain(HeartbeatService service)
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    FakeBuildExecutorImpl buildExecutor = new FakeBuildExecutorImpl();
    runDiamondGraphWithChain(service, buildExecutor, new NoOpMinionBuildProgressTracker());
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
    public ExitCode buildLocallyAndReturnExitCode(
        Iterable<String> targetsToBuild, Optional<Path> pathToBuildReport) {
      buildTargets.addAll(ImmutableList.copyOf((targetsToBuild)));
      return ExitCode.SUCCESS;
    }

    @Override
    public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild) {

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
    public ExitCode waitForBuildToFinish(
        Iterable<String> targetsToBuild,
        List<BuildEngineResult> resultFutures,
        Optional<Path> pathToBuildReport) {
      return ExitCode.SUCCESS;
    }

    @Override
    public CachingBuildEngine getCachingBuildEngine() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
      // Nothing to cleanup in this implementation
    }
  }
}
