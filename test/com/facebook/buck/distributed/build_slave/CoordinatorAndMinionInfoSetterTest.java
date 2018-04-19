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

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildModeInfo;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.distributed.thrift.StampedeId;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CoordinatorAndMinionInfoSetterTest {
  private static final String STANDARD_MINION_QUEUE = "super_minion_queue";
  private static final String LOW_SPEC_MINION_QUEUE = "low_spec_minion_queue";
  private static final StampedeId STAMPEDE_ID = new StampedeId().setId("topspin");

  private DistBuildService service;

  @Before
  public void setUp() throws IOException, InterruptedException {
    service = EasyMock.createMock(DistBuildService.class);
  }

  @Test
  public void testCoordinatorEventListenerWithoutLocalMinion() throws IOException {
    MinionRequirements minionRequirements =
        DistBuildUtil.createMinionRequirements(
            BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR,
            SchedulingEnvironmentType.IDENTICAL_HARDWARE,
            42,
            0);

    runCoordinatorEventListenerTest(minionRequirements, false, 42, 0);
  }

  @Test
  public void testCoordinatorEventListenerWithLocalMinion() throws IOException {
    MinionRequirements minionRequirements =
        DistBuildUtil.createMinionRequirements(
            BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR,
            SchedulingEnvironmentType.IDENTICAL_HARDWARE,
            42,
            0);

    runCoordinatorEventListenerTest(minionRequirements, true, 41, 0);
  }

  @Test
  public void testLowSpecMinionCountIgnoredInIdenticalHardwareEnvironment() throws IOException {
    MinionRequirements minionRequirements =
        DistBuildUtil.createMinionRequirements(
            BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR,
            SchedulingEnvironmentType.IDENTICAL_HARDWARE,
            42,
            2);

    runCoordinatorEventListenerTest(minionRequirements, true, 41, 0);
  }

  @Test
  public void testCoordinatorEventListenerWithoutLocalMinionMixedEnvironment() throws IOException {
    MinionRequirements minionRequirements =
        DistBuildUtil.createMinionRequirements(
            BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR,
            SchedulingEnvironmentType.MIXED_HARDWARE,
            42,
            2);

    runCoordinatorEventListenerTest(minionRequirements, false, 40, 2);
  }

  @Test
  public void testCoordinatorEventListenerWithLocalMinionMixedEnvironment() throws IOException {
    MinionRequirements minionRequirements =
        DistBuildUtil.createMinionRequirements(
            BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR,
            SchedulingEnvironmentType.MIXED_HARDWARE,
            42,
            2);

    runCoordinatorEventListenerTest(minionRequirements, true, 39, 2);
  }

  private void expectEnqueueMinionsCall(
      int expectedNumberOfMinions, String minionQueue, MinionType minionType) throws IOException {
    service.enqueueMinions(
        EasyMock.eq(STAMPEDE_ID),
        EasyMock.eq(expectedNumberOfMinions),
        EasyMock.eq(minionQueue),
        EasyMock.eq(minionType));
    EasyMock.expectLastCall().once();
  }

  private void runCoordinatorEventListenerTest(
      MinionRequirements minionRequirements,
      boolean isLocalMinionAlsoRunning,
      int expectedNumberOfMinions,
      int expectedNumberOfLowSpecMinions)
      throws IOException {
    MinionQueueProvider minionQueueProvider = new MinionQueueProvider();
    minionQueueProvider.registerMinionQueue(MinionType.STANDARD_SPEC, STANDARD_MINION_QUEUE);
    minionQueueProvider.registerMinionQueue(MinionType.LOW_SPEC, LOW_SPEC_MINION_QUEUE);

    CoordinatorEventListener eventListener =
        new CoordinatorEventListener(
            service, STAMPEDE_ID, minionQueueProvider, isLocalMinionAlsoRunning);
    Assert.assertNotNull(eventListener);
    int port = 33;
    String address = "hidden.but.cool.address";
    service.setCoordinator(EasyMock.eq(STAMPEDE_ID), EasyMock.eq(port), EasyMock.eq(address));
    EasyMock.expectLastCall().once();

    BuildJob buildJob =
        new BuildJob()
            .setBuildModeInfo(new BuildModeInfo().setMinionRequirements(minionRequirements));
    EasyMock.expect(service.getCurrentBuildJobState(EasyMock.eq(STAMPEDE_ID)))
        .andReturn(buildJob)
        .once();

    expectEnqueueMinionsCall(
        expectedNumberOfMinions, STANDARD_MINION_QUEUE, MinionType.STANDARD_SPEC);

    if (expectedNumberOfLowSpecMinions > 0) {
      expectEnqueueMinionsCall(
          expectedNumberOfLowSpecMinions, LOW_SPEC_MINION_QUEUE, MinionType.LOW_SPEC);
    }

    EasyMock.replay(service);
    eventListener.onThriftServerStarted(address, port);
    EasyMock.verify(service);
  }
}
