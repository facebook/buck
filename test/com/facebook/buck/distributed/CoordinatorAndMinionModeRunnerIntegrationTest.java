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

import com.facebook.buck.distributed.MinionModeRunnerIntegrationTest.FakeBuilderImpl;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class CoordinatorAndMinionModeRunnerIntegrationTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;
  private static final int MAX_PARALLEL_WORK_UNITS = 10;

  @Test
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {

    int port = ThriftCoordinatorServerIntegrationTest.findRandomOpenPortOnAllLocalInterfaces();
    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createNiceMock(ThriftCoordinatorServer.EventListener.class);
    CoordinatorModeRunner coordinator =
        new CoordinatorModeRunner(
            port, BuildTargetsQueueTest.createDiamondDependencyQueue(), STAMPEDE_ID, eventListener);
    FakeBuilderImpl localBuilder = new FakeBuilderImpl();
    MinionModeRunner minion =
        new MinionModeRunner(
            "localhost",
            port,
            localBuilder,
            STAMPEDE_ID,
            new BuildSlaveRunId().setId("sl7"),
            MAX_PARALLEL_WORK_UNITS,
            EasyMock.createNiceMock(MinionModeRunner.BuildCompletionChecker.class));
    CoordinatorAndMinionModeRunner jointRunner =
        new CoordinatorAndMinionModeRunner(coordinator, minion);
    int exitCode = jointRunner.runAndReturnExitCode();
    Assert.assertEquals(0, exitCode);
    Assert.assertEquals(4, localBuilder.getBuildTargets().size());
    Assert.assertEquals(BuildTargetsQueueTest.TARGET_NAME, localBuilder.getBuildTargets().get(3));
  }
}
