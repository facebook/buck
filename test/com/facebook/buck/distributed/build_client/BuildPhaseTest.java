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

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StreamLogs;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BuildPhaseTest {
  private static final String BUILD_LABEL = "unit_test";
  private static final int POLL_MILLIS = 1;

  private DistBuildService mockDistBuildService;
  private LogStateTracker mockLogStateTracker;
  private ScheduledExecutorService scheduler;
  private BuckVersion buckVersion;
  private ListeningExecutorService directExecutor;
  private BuckEventBus mockEventBus;
  private StampedeId stampedeId;
  private ClientStatsTracker distBuildClientStatsTracker;
  private EventSender eventSender;
  private BuildPhase buildPhase;

  @Before
  public void setUp() throws IOException, InterruptedException {
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    mockLogStateTracker = EasyMock.createMock(LogStateTracker.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    buckVersion = new BuckVersion();
    buckVersion.setGitHash("thishashisamazing");
    distBuildClientStatsTracker = new ClientStatsTracker(BUILD_LABEL);
    directExecutor = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    stampedeId = new StampedeId();
    stampedeId.setId("uber-cool-stampede-id");
    eventSender = new EventSender(mockEventBus);
    buildPhase =
        new BuildPhase(
            mockDistBuildService,
            distBuildClientStatsTracker,
            mockLogStateTracker,
            scheduler,
            POLL_MILLIS);
  }

  @After
  public void tearDown() {
    directExecutor.shutdownNow();
    scheduler.shutdownNow();
  }

  @Test
  public void testFetchingSlaveEvents()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = PostBuildPhaseTest.createBuildJobWithSlaves(stampedeId);
    List<BuildSlaveRunId> buildSlaveRunIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getBuildSlaveRunId)
            .collect(Collectors.toList());

    // Create queries.
    BuildSlaveEventsQuery query0 = new BuildSlaveEventsQuery();
    query0.setBuildSlaveRunId(buildSlaveRunIds.get(0));
    BuildSlaveEventsQuery query1 = new BuildSlaveEventsQuery();
    query0.setBuildSlaveRunId(buildSlaveRunIds.get(1));

    // Create first event.
    BuildSlaveEvent event1 = new BuildSlaveEvent();
    event1.setBuildSlaveRunId(buildSlaveRunIds.get(0));
    event1.setStampedeId(stampedeId);
    event1.setEventType(BuildSlaveEventType.CONSOLE_EVENT);
    BuildSlaveConsoleEvent consoleEvent1 = new BuildSlaveConsoleEvent();
    consoleEvent1.setMessage("This is such fun.");
    consoleEvent1.setSeverity(ConsoleEventSeverity.WARNING);
    consoleEvent1.setTimestampMillis(7);
    event1.setConsoleEvent(consoleEvent1);
    Pair<Integer, BuildSlaveEvent> eventWithSeqId1 = new Pair<>(2, event1);

    // Create second event.
    BuildSlaveEvent event2 = new BuildSlaveEvent();
    event2.setBuildSlaveRunId(buildSlaveRunIds.get(1));
    event2.setStampedeId(stampedeId);
    event2.setEventType(BuildSlaveEventType.CONSOLE_EVENT);
    BuildSlaveConsoleEvent consoleEvent2 = new BuildSlaveConsoleEvent();
    consoleEvent2.setMessage("This is even more fun.");
    consoleEvent2.setSeverity(ConsoleEventSeverity.SEVERE);
    consoleEvent2.setTimestampMillis(5);
    event2.setConsoleEvent(consoleEvent2);
    Pair<Integer, BuildSlaveEvent> eventWithSeqId2 = new Pair<>(1, event2);

    // Set expectations.
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunIds.get(0), 0))
        .andReturn(query0);
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunIds.get(1), 0))
        .andReturn(query1);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query0, query1)))
        .andReturn(ImmutableList.of(eventWithSeqId1, eventWithSeqId2));

    mockEventBus.post(eqConsoleEvent(DistBuildUtil.createConsoleEvent(consoleEvent1)));
    mockEventBus.post(eqConsoleEvent(DistBuildUtil.createConsoleEvent(consoleEvent2)));
    expectLastCall();

    // At the end, also test that sequence ids are being maintained properly.
    expect(
            mockDistBuildService.createBuildSlaveEventsQuery(
                stampedeId, buildSlaveRunIds.get(0), eventWithSeqId1.getFirst() + 1))
        .andReturn(query0);
    expect(
            mockDistBuildService.createBuildSlaveEventsQuery(
                stampedeId, buildSlaveRunIds.get(1), eventWithSeqId2.getFirst() + 1))
        .andReturn(query1);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query0, query1)))
        .andReturn(ImmutableList.of());

    replay(mockDistBuildService);
    replay(mockEventBus);

    // Test that the events are properly fetched and posted onto the Bus.
    buildPhase.fetchAndPostBuildSlaveEventsAsync(job, eventSender, directExecutor).get();
    // Also test that sequence ids are being maintained properly.
    buildPhase.fetchAndPostBuildSlaveEventsAsync(job, eventSender, directExecutor).get();

    verify(mockDistBuildService);
    verify(mockEventBus);
  }

  @Test
  public void testRealTimeLogStreaming()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = PostBuildPhaseTest.createBuildJobWithSlaves(stampedeId);

    // Test that we don't fetch logs if the tracker says we don't need to.
    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());

    // Test that we fetch logs properly if everything looks good.
    LogLineBatchRequest logRequest1 = new LogLineBatchRequest();
    logRequest1.setBatchNumber(5);
    LogLineBatchRequest logRequest2 = new LogLineBatchRequest();
    logRequest2.setBatchNumber(10);
    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of(logRequest1, logRequest2));

    MultiGetBuildSlaveRealTimeLogsResponse logsResponse =
        new MultiGetBuildSlaveRealTimeLogsResponse();
    StreamLogs log1 = new StreamLogs();
    log1.setErrorMessage("unique");
    logsResponse.addToMultiStreamLogs(log1);
    expect(
            mockDistBuildService.fetchSlaveLogLines(
                stampedeId, ImmutableList.of(logRequest1, logRequest2)))
        .andReturn(logsResponse);
    mockLogStateTracker.processStreamLogs(logsResponse.getMultiStreamLogs());
    expectLastCall().once();

    replay(mockDistBuildService);
    replay(mockLogStateTracker);

    // Test that we don't fetch logs if the tracker says we don't need to.
    buildPhase.fetchAndProcessRealTimeSlaveLogsAsync(job, directExecutor).get();
    // Test that we fetch logs properly if everything looks good.
    buildPhase.fetchAndProcessRealTimeSlaveLogsAsync(job, directExecutor).get();

    verify(mockDistBuildService);
    verify(mockLogStateTracker);
  }

  @Test
  public void testFetchingSlaveStatuses()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = PostBuildPhaseTest.createBuildJobWithSlaves(stampedeId);
    List<BuildSlaveRunId> buildSlaveRunIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getBuildSlaveRunId)
            .collect(Collectors.toList());

    BuildSlaveStatus slaveStatus0 = new BuildSlaveStatus();
    slaveStatus0.setStampedeId(stampedeId);
    slaveStatus0.setBuildSlaveRunId(buildSlaveRunIds.get(0));
    slaveStatus0.setTotalRulesCount(5);

    BuildSlaveStatus slaveStatus1 = new BuildSlaveStatus();
    slaveStatus1.setStampedeId(stampedeId);
    slaveStatus1.setBuildSlaveRunId(buildSlaveRunIds.get(1));
    slaveStatus1.setTotalRulesCount(10);

    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunIds.get(0)))
        .andReturn(Optional.of(slaveStatus0));
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunIds.get(1)))
        .andReturn(Optional.of(slaveStatus1));
    replay(mockDistBuildService);

    List<BuildSlaveStatus> slaveStatuses =
        buildPhase.fetchBuildSlaveStatusesAsync(job, directExecutor).get();
    assertEquals(ImmutableSet.copyOf(slaveStatuses), ImmutableSet.of(slaveStatus0, slaveStatus1));

    verify(mockDistBuildService);
  }

  private static ConsoleEvent eqConsoleEvent(ConsoleEvent event) {
    EasyMock.reportMatcher(new ConsoleEventMatcher(event));
    return event;
  }

  private static class ConsoleEventMatcher implements IArgumentMatcher {

    private ConsoleEvent event;

    public ConsoleEventMatcher(ConsoleEvent event) {
      this.event = event;
    }

    @Override
    public boolean matches(Object other) {
      if (other instanceof ConsoleEvent) {
        return event.getMessage().equals(((ConsoleEvent) other).getMessage())
            && event.getLevel().equals(((ConsoleEvent) other).getLevel());
      }
      return false;
    }

    @Override
    public void appendTo(StringBuffer stringBuffer) {
      stringBuffer.append(
          String.format(
              "eqConsoleEvent(message=[%s], level=[%s])", event.getMessage(), event.getLevel()));
    }
  }
}
