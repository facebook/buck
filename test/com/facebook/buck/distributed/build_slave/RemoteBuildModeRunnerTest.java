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
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class RemoteBuildModeRunnerTest {

  private static final StampedeId STAMPEDE_ID = ThriftCoordinatorServerIntegrationTest.STAMPEDE_ID;
  private static final DistBuildService MOCK_SERVICE =
      EasyMock.createNiceMock(DistBuildService.class);
  private static final HeartbeatService HEARTBEAT_SERVICE =
      EasyMock.createNiceMock(HeartbeatService.class);

  @Test
  public void testFinalBuildStatusIsSet() throws IOException, InterruptedException {
    ExitCode expectedExitCode = ExitCode.BUILD_ERROR;

    BuildExecutor buildExecutor = EasyMock.createMock(BuildExecutor.class);
    EasyMock.expect(
            buildExecutor.buildLocallyAndReturnExitCode(EasyMock.anyObject(), EasyMock.anyObject()))
        .andReturn(expectedExitCode)
        .once();
    RemoteBuildModeRunner.FinalBuildStatusSetter setter =
        EasyMock.createMock(RemoteBuildModeRunner.FinalBuildStatusSetter.class);
    setter.setFinalBuildStatus(EasyMock.eq(expectedExitCode.getCode()));
    EasyMock.expectLastCall().once();
    EasyMock.replay(buildExecutor, setter);

    RemoteBuildModeRunner runner =
        new RemoteBuildModeRunner(
            Futures.immediateFuture(buildExecutor),
            Lists.newArrayList(),
            setter,
            MOCK_SERVICE,
            STAMPEDE_ID);
    ExitCode actualExitCode = runner.runAndReturnExitCode(HEARTBEAT_SERVICE);
    Assert.assertEquals(expectedExitCode, actualExitCode);

    EasyMock.verify(buildExecutor, setter);
  }
}
