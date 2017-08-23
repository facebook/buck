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

import static com.facebook.buck.distributed.DistBuildClientStatsTracker.DistBuildClientStat.*;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.distributed.thrift.LogDir;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StreamLogs;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DistBuildClientExecutorTest {

  private DistBuildService mockDistBuildService;
  private DistBuildLogStateTracker mockLogStateTracker;
  private ScheduledExecutorService scheduler;
  private BuckVersion buckVersion;
  private BuildJobState buildJobState;
  private DistBuildCellIndexer distBuildCellIndexer;
  private DistBuildClientExecutor distBuildClientExecutor;
  private ListeningExecutorService directExecutor;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private FakeFileHashCache fakeFileHashCache;
  private BuckEventBus mockEventBus;
  private StampedeId stampedeId;
  private DistBuildClientStatsTracker distBuildClientStatsTracker;
  private static final String REPOSITORY = "repositoryOne";
  private static final String TENANT_ID = "tenantOne";
  private static final String BUILD_LABEL = "unit_test";

  @Before
  public void setUp() throws IOException, InterruptedException {
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    mockLogStateTracker = EasyMock.createMock(DistBuildLogStateTracker.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    buckVersion = new BuckVersion();
    buckVersion.setGitHash("thishashisamazing");
    buildJobState = createMinimalFakeBuildJobState();
    distBuildClientStatsTracker = new DistBuildClientStatsTracker(BUILD_LABEL);
    distBuildCellIndexer = new DistBuildCellIndexer(new TestCellBuilder().build());
    distBuildClientExecutor =
        new DistBuildClientExecutor(
            buildJobState,
            distBuildCellIndexer,
            mockDistBuildService,
            mockLogStateTracker,
            buckVersion,
            distBuildClientStatsTracker,
            scheduler,
            1);

    directExecutor = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
    fakeProjectFilesystem = new FakeProjectFilesystem();
    fakeFileHashCache = FakeFileHashCache.createFromStrings(new HashMap<>());
    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    stampedeId = new StampedeId();
    stampedeId.setId("uber-cool-stampede-id");
  }

  @After
  public void tearDown() {
    directExecutor.shutdownNow();
    scheduler.shutdownNow();
  }

  private BuildJobState createMinimalFakeBuildJobState() {
    BuildJobState state = new BuildJobState();

    BuildJobStateTargetGraph graph = new BuildJobStateTargetGraph();
    BuildJobStateTargetNode node = new BuildJobStateTargetNode();
    node.setCellIndex(42);
    node.setRawNode("awesome-node");
    graph.addToNodes(node);
    state.setTargetGraph(graph);

    BuildJobStateCell cell = new BuildJobStateCell();
    cell.setNameHint("paradise");
    state.putToCells(42, cell);

    state.addToTopLevelTargets("awesome-node");

    BuildJobStateFileHashEntry file1 = new BuildJobStateFileHashEntry();
    file1.setSha1("abcd");

    BuildJobStateFileHashEntry file2 = new BuildJobStateFileHashEntry();
    file1.setSha1("xkcd");

    BuildJobStateFileHashes cellFilehashes = new BuildJobStateFileHashes();
    cellFilehashes.setCellIndex(42);
    cellFilehashes.addToEntries(file1);
    cellFilehashes.addToEntries(file2);
    state.addToFileHashes(cellFilehashes);

    return state;
  }

  private BuildJob createBuildJobWithSlaves() {
    RunId runId1 = new RunId();
    runId1.setId("runid1");
    BuildSlaveInfo slaveInfo1 = new BuildSlaveInfo();
    slaveInfo1.setRunId(runId1);

    RunId runId2 = new RunId();
    runId2.setId("runid2");
    BuildSlaveInfo slaveInfo2 = new BuildSlaveInfo();
    slaveInfo2.setRunId(runId2);

    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);
    job.putToSlaveInfoByRunId(runId1.getId(), slaveInfo1);
    job.putToSlaveInfoByRunId(runId2.getId(), slaveInfo2);

    return job;
  }

  @Test
  public void testFetchingSlaveStatuses()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = createBuildJobWithSlaves();
    List<RunId> runIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getRunId)
            .collect(Collectors.toList());

    BuildSlaveStatus slaveStatus0 = new BuildSlaveStatus();
    slaveStatus0.setStampedeId(stampedeId);
    slaveStatus0.setRunId(runIds.get(0));
    slaveStatus0.setTotalRulesCount(5);

    BuildSlaveStatus slaveStatus1 = new BuildSlaveStatus();
    slaveStatus1.setStampedeId(stampedeId);
    slaveStatus1.setRunId(runIds.get(1));
    slaveStatus1.setTotalRulesCount(10);

    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, runIds.get(0)))
        .andReturn(Optional.of(slaveStatus0));
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, runIds.get(1)))
        .andReturn(Optional.of(slaveStatus1));
    replay(mockDistBuildService);

    List<BuildSlaveStatus> slaveStatuses =
        distBuildClientExecutor.fetchBuildSlaveStatusesAsync(job, directExecutor).get();
    assertEquals(ImmutableSet.copyOf(slaveStatuses), ImmutableSet.of(slaveStatus0, slaveStatus1));

    verify(mockDistBuildService);
  }

  @Test
  public void testFetchingSlaveEvents()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = createBuildJobWithSlaves();
    List<RunId> runIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getRunId)
            .collect(Collectors.toList());

    // Create queries.
    BuildSlaveEventsQuery query0 = new BuildSlaveEventsQuery();
    query0.setRunId(runIds.get(0));
    BuildSlaveEventsQuery query1 = new BuildSlaveEventsQuery();
    query0.setRunId(runIds.get(1));

    // Create first event.
    BuildSlaveEvent event1 = new BuildSlaveEvent();
    event1.setRunId(runIds.get(0));
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
    event2.setRunId(runIds.get(1));
    event2.setStampedeId(stampedeId);
    event2.setEventType(BuildSlaveEventType.CONSOLE_EVENT);
    BuildSlaveConsoleEvent consoleEvent2 = new BuildSlaveConsoleEvent();
    consoleEvent2.setMessage("This is even more fun.");
    consoleEvent2.setSeverity(ConsoleEventSeverity.SEVERE);
    consoleEvent2.setTimestampMillis(5);
    event2.setConsoleEvent(consoleEvent2);
    Pair<Integer, BuildSlaveEvent> eventWithSeqId2 = new Pair<>(1, event2);

    // Set expectations.
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, runIds.get(0), 0))
        .andReturn(query0);
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, runIds.get(1), 0))
        .andReturn(query1);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query0, query1)))
        .andReturn(ImmutableList.of(eventWithSeqId1, eventWithSeqId2));

    mockEventBus.post(eqConsoleEvent(DistBuildUtil.createConsoleEvent(consoleEvent1)));
    mockEventBus.post(eqConsoleEvent(DistBuildUtil.createConsoleEvent(consoleEvent2)));
    expectLastCall();

    // At the end, also test that sequence ids are being maintained properly.
    expect(
            mockDistBuildService.createBuildSlaveEventsQuery(
                stampedeId, runIds.get(0), eventWithSeqId1.getFirst() + 1))
        .andReturn(query0);
    expect(
            mockDistBuildService.createBuildSlaveEventsQuery(
                stampedeId, runIds.get(1), eventWithSeqId2.getFirst() + 1))
        .andReturn(query1);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query0, query1)))
        .andReturn(ImmutableList.of());

    replay(mockDistBuildService);
    replay(mockEventBus);

    // Test that the events are properly fetched and posted onto the Bus.
    distBuildClientExecutor
        .fetchAndPostBuildSlaveEventsAsync(job, mockEventBus, directExecutor)
        .get();
    // Also test that sequence ids are being maintained properly.
    distBuildClientExecutor
        .fetchAndPostBuildSlaveEventsAsync(job, mockEventBus, directExecutor)
        .get();

    verify(mockDistBuildService);
    verify(mockEventBus);
  }

  @Test
  public void testRealTimeLogStreaming()
      throws IOException, ExecutionException, InterruptedException {
    final BuildJob job = createBuildJobWithSlaves();

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
    distBuildClientExecutor.fetchAndProcessRealTimeSlaveLogsAsync(job, directExecutor).get();
    // Test that we fetch logs properly if everything looks good.
    distBuildClientExecutor.fetchAndProcessRealTimeSlaveLogsAsync(job, directExecutor).get();

    verify(mockDistBuildService);
    verify(mockLogStateTracker);
  }

  @Test
  public void testMaterializingLogDirs() throws IOException {
    final BuildJob job = createBuildJobWithSlaves();
    List<RunId> runIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getRunId)
            .collect(Collectors.toList());

    LogDir logDir0 = new LogDir();
    logDir0.setRunId(runIds.get(0));
    logDir0.setData("Here is some data.".getBytes());
    LogDir logDir1 = new LogDir();
    logDir1.setRunId(runIds.get(1));
    logDir1.setData("Here is some more data.".getBytes());

    MultiGetBuildSlaveLogDirResponse logDirResponse = new MultiGetBuildSlaveLogDirResponse();
    logDirResponse.addToLogDirs(logDir0);
    logDirResponse.addToLogDirs(logDir1);
    expect(mockLogStateTracker.runIdsToMaterializeLogDirsFor(job.getSlaveInfoByRunId().values()))
        .andReturn(runIds);
    expect(mockDistBuildService.fetchBuildSlaveLogDir(stampedeId, runIds))
        .andReturn(logDirResponse);
    mockLogStateTracker.materializeLogDirs(ImmutableList.of(logDir0, logDir1));
    expectLastCall().once();

    replay(mockLogStateTracker);
    replay(mockDistBuildService);

    distBuildClientExecutor.materializeSlaveLogDirs(job);

    verify(mockLogStateTracker);
    verify(mockDistBuildService);
  }

  @Test
  public void testPublishingBuildSlaveFinishedStats() throws IOException {
    final BuildJob job = createBuildJobWithSlaves();
    List<RunId> runIds =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(BuildSlaveInfo::getRunId)
            .collect(Collectors.toList());

    List<BuildSlaveFinishedStats> finishedStatsList = new ArrayList<>();

    // Return empty stats for the first slave and test the expected response.
    expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, runIds.get(0)))
        .andReturn(Optional.empty());
    finishedStatsList.add(
        new BuildSlaveFinishedStats()
            .setBuildSlaveStatus(
                new BuildSlaveStatus().setStampedeId(stampedeId).setRunId(runIds.get(0))));

    for (int idx = 1; idx < runIds.size(); ++idx) {
      RunId runId = runIds.get(idx);
      BuildSlaveFinishedStats finishedStats =
          new BuildSlaveFinishedStats()
              .setBuildSlaveStatus(new BuildSlaveStatus().setStampedeId(stampedeId).setRunId(runId))
              .setExitCode(idx);

      finishedStatsList.add(finishedStats);
      expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, runId))
          .andReturn(Optional.of(finishedStats));
    }

    Capture<ClientSideBuildSlaveFinishedStatsEvent> capturedEvent = EasyMock.newCapture();
    mockEventBus.post(capture(capturedEvent));
    expectLastCall().times(1);

    replay(mockDistBuildService);
    replay(mockEventBus);

    distBuildClientExecutor.publishBuildSlaveFinishedStatsEvent(job, mockEventBus, directExecutor);

    verify(mockDistBuildService);
    verify(mockEventBus);

    assertEquals(finishedStatsList, capturedEvent.getValue().getBuildSlaveFinishedStats());
  }

  /**
   * This test sees that executeAndPrintFailuresToEventBus(...) does the following: 1. Initiates the
   * build by uploading missing source files, dot files and target graph. 2. Kicks off the build. 3.
   * Fetches the status of the build, status of the slaves, console events and real-time logs in
   * every status loop. 4. Materializes log directories once the build finishes.
   *
   * <p>
   *
   * <p>Individual methods for fetching the status, logs, posting the events, etc. are tested
   * separately.
   */
  @Test
  public void testOrderlyExecution() throws IOException, InterruptedException {
    final RunId runId = new RunId();
    runId.setId("my-fav-runid");
    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    expect(mockDistBuildService.createBuild(BuildMode.REMOTE_BUILD, 1, REPOSITORY, TENANT_ID))
        .andReturn(job);
    expect(
            mockDistBuildService.uploadMissingFilesAsync(
                distBuildCellIndexer.getLocalFilesystemsByCellIndex(),
                buildJobState.fileHashes,
                distBuildClientStatsTracker,
                directExecutor))
        .andReturn(Futures.immediateFuture(null));
    expect(
            mockDistBuildService.uploadBuckDotFilesAsync(
                stampedeId,
                fakeProjectFilesystem,
                fakeFileHashCache,
                distBuildClientStatsTracker,
                directExecutor))
        .andReturn(Futures.immediateFuture(null));
    mockDistBuildService.uploadTargetGraph(buildJobState, stampedeId, distBuildClientStatsTracker);
    expectLastCall().once();
    mockDistBuildService.setBuckVersion(stampedeId, buckVersion, distBuildClientStatsTracker);
    expectLastCall().once();

    // There's no point checking the DistBuildStatusEvent, since we don't know what 'stage' the
    // executor wants to print. Just check that it is called at least once in every status loop.
    mockEventBus.post(isA(DistBuildStatusEvent.class));
    expectLastCall().times(4, 1000);

    ////////////////////////////////////////////////////
    ///////////////// BUILD STARTS NOW /////////////////
    ////////////////////////////////////////////////////

    job = job.deepCopy(); // new copy
    job.setBuckVersion(buckVersion);
    job.setStatus(BuildStatus.QUEUED);
    expect(mockDistBuildService.startBuild(stampedeId)).andReturn(job);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job).times(2);

    ////////////////////////////////////////////////////
    ////////// STATUS LOOP WITHOUT SLAVE INFO //////////
    ////////////////////////////////////////////////////
    job = job.deepCopy(); // new copy
    job.setStatus(BuildStatus.BUILDING);

    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    ////////////////////////////////////////////////////
    /////////// STATUS LOOP WITH SLAVE INFO ////////////
    ////////////////////////////////////////////////////

    job = job.deepCopy(); // new copy
    BuildSlaveInfo slaveInfo1 = new BuildSlaveInfo();
    slaveInfo1.setRunId(runId);
    job.putToSlaveInfoByRunId(runId.getId(), slaveInfo1);

    BuildSlaveEventsQuery query = new BuildSlaveEventsQuery();
    query.setRunId(runId);

    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setRunId(runId);
    slaveStatus.setTotalRulesCount(5);

    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, runId, 0)).andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, runId))
        .andReturn(Optional.empty());

    ////////////////////////////////////////////////////
    //////////////// FINAL STATUS LOOP /////////////////
    ////////////////////////////////////////////////////

    job = job.deepCopy(); // new copy
    job.setStatus(BuildStatus.FAILED);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, runId))
        .andReturn(Optional.of(slaveStatus));
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, runId, 0)).andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, runId))
        .andReturn(Optional.empty());
    mockEventBus.post(anyObject(ClientSideBuildSlaveFinishedStatsEvent.class));
    expectLastCall().times(1);

    expect(mockLogStateTracker.runIdsToMaterializeLogDirsFor(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());

    replay(mockDistBuildService);
    replay(mockEventBus);
    replay(mockLogStateTracker);

    // Normally LOCAL_PREPARATION get started in BuildCommand, so simulate that here,
    // otherwise when we stop the timer it will fail with an exception about not being started.
    distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
    distBuildClientExecutor.executeAndPrintFailuresToEventBus(
        directExecutor,
        fakeProjectFilesystem,
        fakeFileHashCache,
        mockEventBus,
        BuildMode.REMOTE_BUILD,
        1,
        REPOSITORY,
        TENANT_ID);

    verify(mockDistBuildService);
    verify(mockLogStateTracker);
    verify(mockEventBus);
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

  private static ConsoleEvent eqConsoleEvent(ConsoleEvent event) {
    EasyMock.reportMatcher(new ConsoleEventMatcher(event));
    return event;
  }
}
