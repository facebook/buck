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

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RemoteBuildRuleSynchronizer;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BuildControllerTest {
  private static final String REPOSITORY = "repositoryOne";
  private static final String TENANT_ID = "tenantOne";
  private static final String BUILD_LABEL = "unit_test";

  private DistBuildService mockDistBuildService;
  private LogStateTracker mockLogStateTracker;
  private ScheduledExecutorService scheduler;
  private BuckVersion buckVersion;
  private DistBuildCellIndexer distBuildCellIndexer;
  private ListeningExecutorService directExecutor;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private FakeFileHashCache fakeFileHashCache;
  private BuckEventBus mockEventBus;
  private StampedeId stampedeId;
  private ClientStatsTracker distBuildClientStatsTracker;
  private BuildId buildId;

  @Before
  public void setUp() throws IOException, InterruptedException {
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    mockLogStateTracker = EasyMock.createMock(LogStateTracker.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    buckVersion = new BuckVersion();
    buckVersion.setGitHash("thishashisamazing");
    distBuildClientStatsTracker = new ClientStatsTracker(BUILD_LABEL);
    distBuildCellIndexer = new DistBuildCellIndexer(new TestCellBuilder().build());
    directExecutor = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
    fakeProjectFilesystem = new FakeProjectFilesystem();
    fakeFileHashCache = FakeFileHashCache.createFromStrings(new HashMap<>());
    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    stampedeId = new StampedeId();
    stampedeId.setId("uber-cool-stampede-id");
    buildId = new BuildId();
  }

  private BuildController createController(ListenableFuture<BuildJobState> asyncBuildJobState) {
    return new BuildController(
        asyncBuildJobState,
        distBuildCellIndexer,
        mockDistBuildService,
        mockLogStateTracker,
        buckVersion,
        distBuildClientStatsTracker,
        scheduler,
        0,
        1,
        true,
        new RemoteBuildRuleSynchronizer());
  }

  private void runBuildWithController(BuildController buildController)
      throws IOException, InterruptedException {
    // Normally LOCAL_PREPARATION get started in BuildCommand, so simulate that here,
    // otherwise when we stop the timer it will fail with an exception about not being started.
    distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
    buildController.executeAndPrintFailuresToEventBus(
        directExecutor,
        fakeProjectFilesystem,
        fakeFileHashCache,
        mockEventBus,
        buildId,
        BuildMode.REMOTE_BUILD,
        1,
        REPOSITORY,
        TENANT_ID);
  }

  @After
  public void tearDown() {
    directExecutor.shutdownNow();
    scheduler.shutdownNow();
  }

  public static BuildJobState createMinimalFakeBuildJobState() {
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
    final BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("my-fav-runid");
    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    final BuildJobState buildJobState = createMinimalFakeBuildJobState();

    expect(
            mockDistBuildService.createBuild(
                buildId, BuildMode.REMOTE_BUILD, 1, REPOSITORY, TENANT_ID))
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
    mockEventBus.post(isA(DistBuildCreatedEvent.class));
    expectLastCall().times(1);
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
    slaveInfo1.setBuildSlaveRunId(buildSlaveRunId);
    job.putToSlaveInfoByRunId(buildSlaveRunId.getId(), slaveInfo1);

    BuildSlaveEventsQuery query = new BuildSlaveEventsQuery();
    query.setBuildSlaveRunId(buildSlaveRunId);

    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setBuildSlaveRunId(buildSlaveRunId);
    slaveStatus.setTotalRulesCount(5);

    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 0))
        .andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunId))
        .andReturn(Optional.empty());

    ////////////////////////////////////////////////////
    //////////////// TOP-LEVEL FINAL STATUS ////////////
    ////////////////////////////////////////////////////

    job = job.deepCopy(); // new copy
    job.setStatus(BuildStatus.FAILED);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values()))
        .andReturn(ImmutableList.of());
    expect(mockLogStateTracker.getBuildSlaveLogsMaterializer())
        .andReturn(createNiceMock(BuildSlaveLogsMaterializer.class))
        .once();
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunId))
        .andReturn(Optional.of(slaveStatus));
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 0))
        .andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, buildSlaveRunId))
        .andReturn(Optional.empty());

    ////////////////////////////////////////////////////
    //////////////// FINAL SLAVE STATUS ////////////////
    ////////////////////////////////////////////////////

    // After the top level build status has been set, the slave also sets its own status.
    // This will complete the build.
    job = job.deepCopy(); // new copy
    slaveInfo1.setStatus(BuildStatus.FINISHED_SUCCESSFULLY);
    job.putToSlaveInfoByRunId(buildSlaveRunId.getId(), slaveInfo1);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    mockEventBus.post(isA(ClientSideBuildSlaveFinishedStatsEvent.class));
    expectLastCall().times(1);

    replay(mockDistBuildService);
    replay(mockEventBus);
    replay(mockLogStateTracker);

    runBuildWithController(createController(Futures.immediateFuture(buildJobState)));

    verify(mockDistBuildService);
    verify(mockLogStateTracker);
    verify(mockEventBus);
  }
}
