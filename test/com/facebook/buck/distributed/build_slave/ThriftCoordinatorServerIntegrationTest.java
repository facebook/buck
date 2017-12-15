/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.distributed.build_slave.ThriftCoordinatorServer.ExitState;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.listener.NoOpBuildRuleFinishedPublisher;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.OptionalInt;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class ThriftCoordinatorServerIntegrationTest {
  public static final StampedeId STAMPEDE_ID = new StampedeId().setId("down the line");

  private static final String MINION_ID = "super cool minion";
  private static final int MAX_WORK_UNITS_TO_FETCH = 10;
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000;

  @Test
  public void testMakingSimpleRequest() throws IOException {
    try (ThriftCoordinatorServer server =
            createServerOnRandomPort(BuildTargetsQueueFactory.newEmptyQueue());
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", STAMPEDE_ID, CONNECTION_TIMEOUT_MILLIS)) {
      server.start();
      client.start(server.getPort());
      GetWorkResponse response =
          client.getWork(MINION_ID, 0, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH);
      Assert.assertNotNull(response);
      Assert.assertFalse(response.isContinueBuilding()); // Build is finished
    }
  }

  @Test
  public void testThriftServerWithDiamondGraph() throws IOException, NoSuchBuildTargetException {
    BuildTargetsQueue diamondQueue = BuildTargetsQueueTest.createDiamondDependencyQueue();

    Capture<ExitState> exitState = EasyMock.newCapture();
    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createMock(ThriftCoordinatorServer.EventListener.class);
    eventListener.onThriftServerStarted(EasyMock.anyString(), EasyMock.gt(0));
    EasyMock.expectLastCall().once();
    eventListener.onThriftServerClosing(EasyMock.capture(exitState));
    EasyMock.expectLastCall().once();
    EasyMock.replay(eventListener);

    try (ThriftCoordinatorServer server =
            createCoordinatorServer(OptionalInt.empty(), diamondQueue, eventListener);
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", STAMPEDE_ID, CONNECTION_TIMEOUT_MILLIS)) {
      server.start();
      client.start(server.getPort());

      GetWorkResponse responseOne =
          client.getWork(MINION_ID, 0, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseOne.getWorkUnitsSize(), 1);
      Assert.assertTrue(responseOne.isContinueBuilding());

      GetWorkResponse responseTwo =
          client.getWork(
              MINION_ID,
              0,
              ImmutableList.of(BuildTargetsQueueTest.LEAF_TARGET),
              MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseTwo.getWorkUnitsSize(), 2);
      Assert.assertTrue(responseTwo.isContinueBuilding());

      GetWorkResponse responseThree =
          client.getWork(
              MINION_ID,
              0,
              ImmutableList.of(
                  BuildTargetsQueueTest.LEFT_TARGET, BuildTargetsQueueTest.RIGHT_TARGET),
              MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseThree.getWorkUnitsSize(), 1);
      Assert.assertTrue(responseThree.isContinueBuilding());

      GetWorkResponse responseFour =
          client.getWork(
              MINION_ID,
              0,
              ImmutableList.of(BuildTargetsQueueTest.ROOT_TARGET),
              MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseFour.getWorkUnitsSize(), 0);
      Assert.assertFalse(responseFour.isContinueBuilding());

      // Ensure that subsequent invocations of GetWork do not crash.
      GetWorkResponse responseFive =
          client.getWork(
              MINION_ID,
              0,
              ImmutableList.of(BuildTargetsQueueTest.ROOT_TARGET),
              MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseFive.getWorkUnitsSize(), 0);
      Assert.assertFalse(responseFive.isContinueBuilding());
    }

    Assert.assertEquals(1, exitState.getValues().size());
    Assert.assertEquals(0, exitState.getValue().getExitCode());
    EasyMock.verify(eventListener);
  }

  public static ThriftCoordinatorServer createServerOnRandomPort(BuildTargetsQueue queue)
      throws IOException {
    return createCoordinatorServer(OptionalInt.empty(), queue);
  }

  private static ThriftCoordinatorServer createCoordinatorServer(
      OptionalInt port, BuildTargetsQueue queue) {
    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createNiceMock(ThriftCoordinatorServer.EventListener.class);
    return createCoordinatorServer(port, queue, eventListener);
  }

  private static ThriftCoordinatorServer createCoordinatorServer(
      OptionalInt port,
      BuildTargetsQueue queue,
      ThriftCoordinatorServer.EventListener eventListener) {
    SettableFuture<BuildTargetsQueue> future = SettableFuture.create();
    future.set(queue);
    return new ThriftCoordinatorServer(
        port,
        future,
        STAMPEDE_ID,
        eventListener,
        new NoOpBuildRuleFinishedPublisher(),
        EasyMock.createNiceMock(MinionHealthTracker.class),
        EasyMock.createNiceMock(DistBuildService.class));
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testTerminateOnException() throws Exception {
    StampedeId wrongStampedeId = new StampedeId().setId("not-" + STAMPEDE_ID.id);

    try (ThriftCoordinatorServer server =
            createCoordinatorServer(OptionalInt.empty(), BuildTargetsQueueFactory.newEmptyQueue());
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", wrongStampedeId, CONNECTION_TIMEOUT_MILLIS)) {
      server.start();
      client.start(server.getPort());
      try {
        client.getWork(MINION_ID, 0, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH);
        Assert.fail("expecting exception, because stampede id mismatches");
      } catch (Exception e) {
        // expected
      }

      Assert.assertEquals(
          ThriftCoordinatorServer.GET_WORK_FAILED_EXIT_CODE,
          server.waitUntilBuildCompletesAndReturnExitCode());
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testTerminateWhenBuildTargetsQueueCreationThrows() throws Exception {
    SettableFuture<BuildTargetsQueue> queueFuture = SettableFuture.create();
    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createNiceMock(ThriftCoordinatorServer.EventListener.class);
    try (ThriftCoordinatorServer server =
            new ThriftCoordinatorServer(
                OptionalInt.empty(),
                queueFuture,
                STAMPEDE_ID,
                eventListener,
                new NoOpBuildRuleFinishedPublisher(),
                EasyMock.createNiceMock(MinionHealthTracker.class),
                EasyMock.createNiceMock(DistBuildService.class));
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", STAMPEDE_ID, CONNECTION_TIMEOUT_MILLIS)) {
      server.start();
      client.start(server.getPort());
      queueFuture.setException(new RuntimeException());
      try {
        client.getWork(MINION_ID, 0, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH);
        Assert.fail("expecting exception, because stampede id mismatches");
      } catch (Exception e) {
        // expected.
      }

      Assert.assertEquals(
          ThriftCoordinatorServer.GET_WORK_FAILED_EXIT_CODE,
          server.waitUntilBuildCompletesAndReturnExitCode());
    }
  }
}
