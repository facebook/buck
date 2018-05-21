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

package com.facebook.buck.distributed.build_client;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PostBuildPhaseTest {
  private static final String BUILD_LABEL = "unit_test";
  private static final String MINION_TYPE = "standard_type";

  private DistBuildService mockDistBuildService;
  private LogStateTracker mockLogStateTracker;
  private ScheduledExecutorService scheduler;
  private BuckVersion buckVersion;
  private ListeningExecutorService directExecutor;
  private BuckEventBus mockEventBus;
  private StampedeId stampedeId;
  private ClientStatsTracker distBuildClientStatsTracker;
  private PostBuildPhase postBuildPhase;
  private ConsoleEventsDispatcher consoleEventsDispatcher;

  @Before
  public void setUp() throws IOException, InterruptedException {
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    mockLogStateTracker = EasyMock.createMock(LogStateTracker.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    buckVersion = new BuckVersion();
    buckVersion.setGitHash("thishashisamazing");
    distBuildClientStatsTracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    postBuildPhase =
        new PostBuildPhase(
            mockDistBuildService, distBuildClientStatsTracker, mockLogStateTracker, 0, false);

    directExecutor = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    consoleEventsDispatcher = new ConsoleEventsDispatcher(mockEventBus);
    stampedeId = new StampedeId();
    stampedeId.setId("uber-cool-stampede-id");
  }

  @After
  public void tearDown() {
    directExecutor.shutdownNow();
    scheduler.shutdownNow();
  }

  public static BuildJob createBuildJobWithSlaves(StampedeId stampedeId) {
    BuildSlaveRunId buildSlaveRunId1 = new BuildSlaveRunId();
    buildSlaveRunId1.setId("runid1");
    BuildSlaveInfo slaveInfo1 = new BuildSlaveInfo();
    slaveInfo1.setBuildSlaveRunId(buildSlaveRunId1);

    BuildSlaveRunId buildSlaveRunId2 = new BuildSlaveRunId();
    buildSlaveRunId2.setId("runid2");
    BuildSlaveInfo slaveInfo2 = new BuildSlaveInfo();
    slaveInfo2.setBuildSlaveRunId(buildSlaveRunId2);

    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);
    job.addToBuildSlaves(slaveInfo1);
    job.addToBuildSlaves(slaveInfo2);

    return job;
  }

  @Test
  public void testPublishingBuildSlaveFinishedStats() throws IOException {
    BuildJob job = PostBuildPhaseTest.createBuildJobWithSlaves(stampedeId);
    List<BuildSlaveRunId> buildSlaveRunIds =
        job.getBuildSlaves()
            .stream()
            .map(BuildSlaveInfo::getBuildSlaveRunId)
            .collect(Collectors.toList());

    List<BuildSlaveFinishedStats> finishedStatsList = new ArrayList<>();

    // Return empty stats for the first slave and test the expected response.
    expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, buildSlaveRunIds.get(0)))
        .andReturn(Optional.empty());
    finishedStatsList.add(
        new BuildSlaveFinishedStats()
            .setBuildSlaveStatus(
                new BuildSlaveStatus()
                    .setStampedeId(stampedeId)
                    .setBuildSlaveRunId(buildSlaveRunIds.get(0))));

    for (int idx = 1; idx < buildSlaveRunIds.size(); ++idx) {
      BuildSlaveRunId buildSlaveRunId = buildSlaveRunIds.get(idx);
      BuildSlaveFinishedStats finishedStats =
          new BuildSlaveFinishedStats()
              .setBuildSlaveStatus(
                  new BuildSlaveStatus()
                      .setStampedeId(stampedeId)
                      .setBuildSlaveRunId(buildSlaveRunId))
              .setExitCode(idx);

      finishedStatsList.add(finishedStats);
      expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, buildSlaveRunId))
          .andReturn(Optional.of(finishedStats));
    }

    Capture<ClientSideBuildSlaveFinishedStatsEvent> capturedEvent = EasyMock.newCapture();
    mockEventBus.post(capture(capturedEvent));
    expectLastCall().times(1);

    replay(mockDistBuildService);
    replay(mockEventBus);

    postBuildPhase.publishBuildSlaveFinishedStatsEvent(
        job, directExecutor, consoleEventsDispatcher);

    verify(mockDistBuildService);
    verify(mockEventBus);

    BuildSlaveStats capturedStats = capturedEvent.getValue().getBuildSlaveFinishedStats();
    assertEquals(stampedeId, capturedStats.getStampedeId());
    assertEquals(2, capturedStats.getBuildSlaveStats().size());
    List<Optional<BuildSlaveFinishedStats>> actualFinishedStats =
        capturedStats
            .getBuildSlaveStats()
            .entrySet()
            .stream()
            .sorted(Comparator.comparing(Entry::getKey))
            .map(x -> x.getValue())
            .collect(Collectors.toList());
    assertEquals(Optional.empty(), actualFinishedStats.get(0));
    assertEquals(finishedStatsList.get(1), actualFinishedStats.get(1).get());
  }
}
