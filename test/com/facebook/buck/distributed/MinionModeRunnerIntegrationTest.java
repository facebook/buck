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

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.command.Builder;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.slb.ThriftException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class MinionModeRunnerIntegrationTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;
  private static final int MAX_PARALLEL_WORK_UNITS = 10;

  @Test(expected = ThriftException.class)
  public void testMinionWithoutServerAndWithUnfinishedBuild()
      throws IOException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> false;
    FakeBuilderImpl localBuilder = new FakeBuilderImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            42,
            localBuilder,
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl1"),
            MAX_PARALLEL_WORK_UNITS,
            checker);

    minion.runAndReturnExitCode();
    Assert.fail("The previous line should've thrown an exception.");
  }

  @Test
  public void testMinionWithoutServerAndWithFinishedBuild()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> true;
    FakeBuilderImpl localBuilder = new FakeBuilderImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            42,
            localBuilder,
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl2"),
            MAX_PARALLEL_WORK_UNITS,
            checker);

    int exitCode = minion.runAndReturnExitCode();
    // Server does not exit because the build has already been marked as finished.
    Assert.assertEquals(0, exitCode);
  }

  @Test
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    MinionModeRunner.BuildCompletionChecker checker = () -> false;
    try (ThriftCoordinatorServer server = createServer()) {
      server.start();
      FakeBuilderImpl localBuilder = new FakeBuilderImpl();
      MinionModeRunner minion =
          new MinionModeRunner(
              "localhost",
              server.getPort(),
              localBuilder,
              STAMPEDE_ID,
              new BuildSlaveRunId().setId("sl3"),
              MAX_PARALLEL_WORK_UNITS,
              checker);
      int exitCode = minion.runAndReturnExitCode();
      Assert.assertEquals(0, exitCode);
      Assert.assertEquals(4, localBuilder.getBuildTargets().size());
      Assert.assertEquals(BuildTargetsQueueTest.TARGET_NAME, localBuilder.getBuildTargets().get(3));
    }
  }

  private ThriftCoordinatorServer createServer() throws NoSuchBuildTargetException, IOException {
    BuildTargetsQueue queue = BuildTargetsQueueTest.createDiamondDependencyQueue();
    return ThriftCoordinatorServerIntegrationTest.createServerOnRandomPort(queue);
  }

  public static class FakeBuilderImpl implements Builder {

    private final List<String> buildTargets;

    public FakeBuilderImpl() {
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
    public void shutdown() throws IOException {
      // Nothing to cleanup in this implementation
    }
  }
}
