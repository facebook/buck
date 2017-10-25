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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class ThriftCoordinatorServerIntegrationTest {
  public static final StampedeId STAMPEDE_ID = new StampedeId().setId("down the line");

  private static final String MINION_ID = "super cool minion";
  private static final int MAX_WORK_UNITS_TO_FETCH = 10;

  @Test
  public void testMakingSimpleRequest() throws IOException {
    int port = findRandomOpenPortOnAllLocalInterfaces();
    try (ThriftCoordinatorServer server =
            createCoordinatorServer(port, BuildTargetsQueue.newEmptyQueue());
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", port, STAMPEDE_ID)) {
      server.start();
      client.start();
      GetWorkResponse response =
          client.getWork(MINION_ID, 0, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH);
      Assert.assertNotNull(response);
      Assert.assertFalse(response.isContinueBuilding()); // Build is finished
    }
  }

  @Test
  public void testThriftServerWithDiamondGraph() throws IOException, NoSuchBuildTargetException {
    int port = findRandomOpenPortOnAllLocalInterfaces();
    BuildTargetsQueue diamondQueue = BuildTargetsQueueTest.createDiamondDependencyQueue();

    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createMock(ThriftCoordinatorServer.EventListener.class);
    eventListener.onThriftServerStarted(EasyMock.anyString(), EasyMock.eq(port));
    EasyMock.expectLastCall().once();
    eventListener.onThriftServerClosing(0);
    EasyMock.expectLastCall().once();
    EasyMock.replay(eventListener);

    try (ThriftCoordinatorServer server =
            createCoordinatorServer(port, diamondQueue, eventListener);
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", port, STAMPEDE_ID)) {
      server.start();
      client.start();

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
              ImmutableList.of(BuildTargetsQueueTest.TARGET_NAME),
              MAX_WORK_UNITS_TO_FETCH);

      Assert.assertEquals(responseFour.getWorkUnitsSize(), 0);
      Assert.assertFalse(responseFour.isContinueBuilding());
    }

    EasyMock.verify(eventListener);
  }

  public static int findRandomOpenPortOnAllLocalInterfaces() throws IOException {
    return DistBuildSlaveExecutor.getFreePortForCoordinator();
  }

  public static ThriftCoordinatorServer createServerOnRandomPort(BuildTargetsQueue queue)
      throws IOException {
    int port = findRandomOpenPortOnAllLocalInterfaces();
    return createCoordinatorServer(port, queue);
  }

  private static ThriftCoordinatorServer createCoordinatorServer(
      int port, BuildTargetsQueue queue) {
    ThriftCoordinatorServer.EventListener eventListener =
        EasyMock.createNiceMock(ThriftCoordinatorServer.EventListener.class);
    return createCoordinatorServer(port, queue, eventListener);
  }

  private static ThriftCoordinatorServer createCoordinatorServer(
      int port, BuildTargetsQueue queue, ThriftCoordinatorServer.EventListener eventListener) {
    return new ThriftCoordinatorServer(port, queue, STAMPEDE_ID, eventListener);
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testTerminateOnException() throws Exception {
    int port = findRandomOpenPortOnAllLocalInterfaces();
    StampedeId wrongStampedeId = new StampedeId().setId("not-" + STAMPEDE_ID.id);

    try (ThriftCoordinatorServer server =
            createCoordinatorServer(port, BuildTargetsQueue.newEmptyQueue());
        ThriftCoordinatorClient client =
            new ThriftCoordinatorClient("localhost", port, wrongStampedeId)) {
      server.start();
      client.start();
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
}
