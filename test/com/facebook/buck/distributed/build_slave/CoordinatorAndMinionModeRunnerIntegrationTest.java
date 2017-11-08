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

import com.facebook.buck.distributed.build_slave.MinionModeRunnerIntegrationTest.FakeBuildExecutorImpl;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.BuckConstant;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CoordinatorAndMinionModeRunnerIntegrationTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;
  private static final int MAX_PARALLEL_WORK_UNITS = 10;
  private static final long POLL_LOOP_INTERVAL_MILLIS = 8;

  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {

    Path logDirectoryPath = tempDir.getRoot().toPath();

    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createNiceMock(ThriftCoordinatorServer.EventListener.class);
    SettableFuture<BuildTargetsQueue> queueFuture = SettableFuture.create();
    queueFuture.set(BuildTargetsQueueTest.createDiamondDependencyQueue());

    CoordinatorModeRunner coordinator =
        new CoordinatorModeRunner(queueFuture, STAMPEDE_ID, eventListener, logDirectoryPath);
    FakeBuildExecutorImpl localBuilder = new FakeBuildExecutorImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            OptionalInt.empty(),
            localBuilder,
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl7"),
            MAX_PARALLEL_WORK_UNITS,
            EasyMock.createNiceMock(MinionModeRunner.BuildCompletionChecker.class),
            POLL_LOOP_INTERVAL_MILLIS);
    CoordinatorAndMinionModeRunner jointRunner =
        new CoordinatorAndMinionModeRunner(coordinator, minion);
    int exitCode = jointRunner.runAndReturnExitCode();
    Assert.assertEquals(0, exitCode);
    Assert.assertEquals(4, localBuilder.getBuildTargets().size());
    Assert.assertEquals(BuildTargetsQueueTest.TARGET_NAME, localBuilder.getBuildTargets().get(3));

    Path buildTracePath = logDirectoryPath.resolve(BuckConstant.DIST_BUILD_TRACE_FILE_NAME);
    Assert.assertTrue(buildTracePath.toFile().exists());
  }
}
