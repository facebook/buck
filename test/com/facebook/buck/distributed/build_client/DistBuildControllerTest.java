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
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.distributed.BuildSlaveEventWrapper;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildService.DistBuildRejectedException;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.ExitCode;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.ActionAndTargetGraphs;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildEvent.DistBuildFinished;
import com.facebook.buck.rules.BuildInfoStoreManager;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RemoteBuildRuleSynchronizer;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystemFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeInvocationInfoFactory;
import com.facebook.buck.util.concurrent.FakeWeightedListeningExecutorService;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DistBuildControllerTest {
  private static final String REPOSITORY = "repositoryOne";
  private static final String TENANT_ID = "tenantOne";
  private static final String BUILD_LABEL = "unit_test";
  private static final List<String> BUILD_TARGETS = Lists.newArrayList();

  private DistBuildService mockDistBuildService;
  private LogStateTracker mockLogStateTracker;
  private ScheduledExecutorService scheduler;
  private BuckVersion buckVersion;
  private DistBuildCellIndexer distBuildCellIndexer;
  private WeightedListeningExecutorService directExecutor;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private FakeFileHashCache fakeFileHashCache;
  private BuckEventBus mockEventBus;
  private StampedeId stampedeId;
  private ClientStatsTracker distBuildClientStatsTracker;
  private InvocationInfo invocationInfo;

  @Before
  public void setUp() throws IOException, InterruptedException {
    mockDistBuildService = EasyMock.createMock(DistBuildService.class);
    mockLogStateTracker = EasyMock.createMock(LogStateTracker.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    buckVersion = new BuckVersion();
    buckVersion.setGitHash("thishashisamazing");
    distBuildClientStatsTracker = new ClientStatsTracker(BUILD_LABEL);
    distBuildCellIndexer = new DistBuildCellIndexer(new TestCellBuilder().build());
    directExecutor =
        new FakeWeightedListeningExecutorService(MoreExecutors.newDirectExecutorService());
    fakeProjectFilesystem = new FakeProjectFilesystem();
    fakeFileHashCache = FakeFileHashCache.createFromStrings(new HashMap<>());
    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    stampedeId = new StampedeId();
    stampedeId.setId("uber-cool-stampede-id");
    invocationInfo = FakeInvocationInfoFactory.create();
  }

  private DistBuildController createController(ListenableFuture<BuildJobState> asyncBuildJobState) {

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    BuildExecutorArgs executorArgs =
        BuildExecutorArgs.builder()
            .setArtifactCacheFactory(new NoopArtifactCache.NoopArtifactCacheFactory())
            .setBuckEventBus(mockEventBus)
            .setBuildInfoStoreManager(new BuildInfoStoreManager())
            .setClock(new DefaultClock())
            .setConsole(new TestConsole())
            .setPlatform(Platform.detect())
            .setProjectFilesystemFactory(new FakeProjectFilesystemFactory())
            .setRuleKeyConfiguration(
                ConfigRuleKeyConfigurationFactory.create(
                    FakeBuckConfig.builder().build(),
                    BuckPluginManagerFactory.createPluginManager()))
            .setRootCell(
                new TestCellBuilder()
                    .setFilesystem(new FakeProjectFilesystem())
                    .setBuckConfig(buckConfig)
                    .build())
            .build();

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("some_stampede_id");
    AtomicReference<StampedeId> stampedeIdRef = new AtomicReference<>(stampedeId);

    ActionAndTargetGraphs graphs =
        ActionAndTargetGraphs.builder()
            .setActionGraphAndResolver(
                ActionGraphAndResolver.of(
                    createNiceMock(ActionGraph.class), createNiceMock(BuildRuleResolver.class)))
            .setUnversionedTargetGraph(
                TargetGraphAndBuildTargets.of(createNiceMock(TargetGraph.class), ImmutableSet.of()))
            .build();
    return new DistBuildController(
        DistBuildControllerArgs.builder()
            .setBuilderExecutorArgs(executorArgs)
            .setBuckEventBus(mockEventBus)
            .setDistBuildStartedEvent(BuildEvent.distBuildStarted())
            .setTopLevelTargets(ImmutableSet.of())
            .setBuildGraphs(graphs)
            .setAsyncJobState(asyncBuildJobState)
            .setDistBuildCellIndexer(distBuildCellIndexer)
            .setDistBuildService(mockDistBuildService)
            .setDistBuildLogStateTracker(mockLogStateTracker)
            .setBuckVersion(buckVersion)
            .setDistBuildClientStats(distBuildClientStatsTracker)
            .setScheduler(scheduler)
            .setMaxTimeoutWaitingForLogsMillis(0)
            .setStatusPollIntervalMillis(1)
            .setLogMaterializationEnabled(true)
            .setRemoteBuildRuleCompletionNotifier(new RemoteBuildRuleSynchronizer())
            .setStampedeIdReference(stampedeIdRef)
            .setBuildLabel(BUILD_LABEL)
            .build());
  }

  private DistBuildController.ExecutionResult runBuildWithController(
      DistBuildController distBuildController) throws InterruptedException {
    // Normally LOCAL_PREPARATION get started in BuildCommand, so simulate that here,
    // otherwise when we stop the timer it will fail with an exception about not being started.
    distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
    return distBuildController.executeAndPrintFailuresToEventBus(
        directExecutor,
        fakeProjectFilesystem,
        fakeFileHashCache,
        invocationInfo,
        BuildMode.REMOTE_BUILD,
        1,
        REPOSITORY,
        TENANT_ID,
        SettableFuture.create());
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

  @Test
  public void testReturnsExecutionResultOnSyncPreparationFailure()
      throws IOException, InterruptedException {
    BuildJobState buildJobState = createMinimalFakeBuildJobState();

    DistBuildController controller = createController(Futures.immediateFuture(buildJobState));

    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    DistBuildController.ExecutionResult executionResult = runBuildWithController(controller);
    assertEquals(ExitCode.PREPARATION_STEP_FAILED.getCode(), executionResult.exitCode);
  }

  @Test
  public void testReturnsExecutionResultOnAsyncPreparationFailure()
      throws IOException, InterruptedException, DistBuildRejectedException {

    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    // Ensure the synchronous steps succeed
    expect(
            mockDistBuildService.createBuild(
                invocationInfo.getBuildId(),
                BuildMode.REMOTE_BUILD,
                1,
                REPOSITORY,
                TENANT_ID,
                BUILD_TARGETS,
                BUILD_LABEL))
        .andReturn(job);

    expect(
            mockDistBuildService.uploadBuckDotFilesAsync(
                stampedeId,
                fakeProjectFilesystem,
                fakeFileHashCache,
                distBuildClientStatsTracker,
                directExecutor))
        .andReturn(Futures.immediateFuture(null));

    replay(mockDistBuildService);

    // Controller where async step fails
    DistBuildController controller =
        createController(Futures.immediateFailedFuture(new Exception("Async preparation failed")));

    DistBuildController.ExecutionResult executionResult = runBuildWithController(controller);
    assertEquals(ExitCode.PREPARATION_ASYNC_STEP_FAILED.getCode(), executionResult.exitCode);
    assertEquals(stampedeId, executionResult.stampedeId);
  }

  @Test
  public void testReturnsExecutionResultOnDistBuildException()
      throws IOException, InterruptedException, DistBuildRejectedException {
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("my-fav-runid");
    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    BuildJobState buildJobState = createMinimalFakeBuildJobState();

    setupExpectationsForSuccessfulDistBuildPrepStep(job, buildJobState);

    // Throw an exception during distributed build step
    expect(mockDistBuildService.startBuild(stampedeId, true))
        .andThrow(new RuntimeException("Distributed build step local failure"));

    replay(mockDistBuildService);

    DistBuildController.ExecutionResult executionResult =
        runBuildWithController(createController(Futures.immediateFuture(buildJobState)));

    assertEquals(
        ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION.getCode(), executionResult.exitCode);
    assertEquals(stampedeId, executionResult.stampedeId);
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
  public void testOrderlyExecution()
      throws IOException, InterruptedException, DistBuildRejectedException {
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("my-fav-runid");
    BuildJob job = new BuildJob();
    job.setStampedeId(stampedeId);

    BuildJobState buildJobState = createMinimalFakeBuildJobState();

    setupExpectationsForSuccessfulDistBuildPrepStep(job, buildJobState);

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
    expect(mockDistBuildService.startBuild(stampedeId, true)).andReturn(job);
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
    job.addToBuildSlaves(slaveInfo1);

    BuildSlaveEventsQuery query = new BuildSlaveEventsQuery();
    query.setBuildSlaveRunId(buildSlaveRunId);
    query.setFirstEventNumber(0);

    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setBuildSlaveRunId(buildSlaveRunId);
    slaveStatus.setTotalRulesCount(5);

    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getBuildSlaves()))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 0))
        .andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunId))
        .andReturn(Optional.empty())
        .times(2);

    ////////////////////////////////////////////////////
    //////////////// TOP-LEVEL FINAL STATUS ////////////
    ////////////////////////////////////////////////////

    job = job.deepCopy(); // new copy
    job.setStatus(BuildStatus.FAILED);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getBuildSlaves()))
        .andReturn(ImmutableList.of());
    expect(mockLogStateTracker.getBuildSlaveLogsMaterializer())
        .andReturn(createNiceMock(BuildSlaveLogsMaterializer.class))
        .once();
    expect(mockDistBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunId))
        .andReturn(Optional.of(slaveStatus));
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 0))
        .andReturn(query);

    // Signal that all build rules have been published.
    BuildSlaveEvent buildSlaveEvent = new BuildSlaveEvent();
    buildSlaveEvent.setEventType(BuildSlaveEventType.ALL_BUILD_RULES_FINISHED_EVENT);

    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(
            Lists.newArrayList(new BuildSlaveEventWrapper(1, buildSlaveRunId, buildSlaveEvent)));
    expect(mockDistBuildService.fetchBuildSlaveFinishedStats(stampedeId, buildSlaveRunId))
        .andReturn(Optional.empty());

    ////////////////////////////////////////////////////
    //////////////// FINAL SLAVE STATUS ////////////////
    ////////////////////////////////////////////////////

    // After the top level build status has been set, the slave also sets its own status.
    // This will complete the build.
    job = job.deepCopy(); // new copy
    slaveInfo1.setStatus(BuildStatus.FINISHED_SUCCESSFULLY);
    assertEquals(slaveInfo1.getBuildSlaveRunId(), job.getBuildSlaves().get(0).getBuildSlaveRunId());
    job.getBuildSlaves().set(0, slaveInfo1);
    expect(mockDistBuildService.getCurrentBuildJobState(stampedeId)).andReturn(job);

    expect(mockLogStateTracker.createRealtimeLogRequests(job.getBuildSlaves()))
        .andReturn(ImmutableList.of());

    query.setFirstEventNumber(1);
    expect(mockDistBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 2))
        .andReturn(query);
    expect(mockDistBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query)))
        .andReturn(ImmutableList.of());

    mockEventBus.post(isA(ClientSideBuildSlaveFinishedStatsEvent.class));
    expectLastCall().times(1);

    Capture<DistBuildFinished> finishedEvent = Capture.newInstance(CaptureType.LAST);
    mockEventBus.post(capture(finishedEvent));
    expectLastCall().atLeastOnce();

    replay(mockDistBuildService);
    replay(mockEventBus);
    replay(mockLogStateTracker);

    DistBuildController.ExecutionResult executionResult =
        runBuildWithController(createController(Futures.immediateFuture(buildJobState)));

    verify(mockDistBuildService);
    verify(mockLogStateTracker);
    verify(mockEventBus);

    assertEquals(
        ExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE.getCode(), executionResult.exitCode);
    assertEquals(
        ExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE.getCode(),
        finishedEvent.getValue().getExitCode());
  }

  // Sets up mock expectations for a successful distributed build preparation step
  private void setupExpectationsForSuccessfulDistBuildPrepStep(
      BuildJob job, BuildJobState buildJobState) throws IOException, DistBuildRejectedException {
    expect(
            mockDistBuildService.createBuild(
                invocationInfo.getBuildId(),
                BuildMode.REMOTE_BUILD,
                1,
                REPOSITORY,
                TENANT_ID,
                BUILD_TARGETS,
                BUILD_LABEL))
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
  }
}
