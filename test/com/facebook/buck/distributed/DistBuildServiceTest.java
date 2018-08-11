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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsResponse;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsRange;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.BuildStatusResponse;
import com.facebook.buck.distributed.thrift.CASContainsResponse;
import com.facebook.buck.distributed.thrift.CreateBuildResponse;
import com.facebook.buck.distributed.thrift.EnqueueMinionsRequest;
import com.facebook.buck.distributed.thrift.EnqueueMinionsResponse;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveFinishedStatsRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveFinishedStatsResponse;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusResponse;
import com.facebook.buck.distributed.thrift.FetchSourceFilesRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesResponse;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsResponse;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.distributed.thrift.SequencedBuildSlaveEvent;
import com.facebook.buck.distributed.thrift.SetCoordinatorRequest;
import com.facebook.buck.distributed.thrift.SetCoordinatorResponse;
import com.facebook.buck.distributed.thrift.SetFinalBuildStatusRequest;
import com.facebook.buck.distributed.thrift.SetFinalBuildStatusResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StartBuildResponse;
import com.facebook.buck.distributed.thrift.StoreBuildSlaveFinishedStatsRequest;
import com.facebook.buck.distributed.thrift.StoreBuildSlaveFinishedStatsResponse;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusResponse;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

// TODO(ruibm, shivanker): Revisit these tests and clean them up.
public class DistBuildServiceTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private FrontendService frontendService;
  private DistBuildService distBuildService;
  private ListeningExecutorService executor;
  private ClientStatsTracker distBuildClientStatsTracker;
  private static final SchedulingEnvironmentType ENVIRONMENT_TYPE =
      SchedulingEnvironmentType.IDENTICAL_HARDWARE;
  private static final String REPOSITORY = "repositoryOne";
  private static final String TENANT_ID = "tenantOne";
  private static final String BUILD_LABEL = "unit_test";
  private static final String MINION_TYPE = "standard_type";
  private static final String USERNAME = "unit_test_user";
  private static final List<String> BUILD_TARGETS = Lists.newArrayList();

  @Before
  public void setUp() throws IOException, InterruptedException {
    frontendService = EasyMock.createStrictMock(FrontendService.class);
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    distBuildService = new DistBuildService(frontendService, USERNAME);
    distBuildClientStatsTracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void canUploadTargetGraph() throws IOException {
    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.STORE_BUILD_GRAPH);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();

    EasyMock.replay(frontendService);

    BuildJobState buildJobState = new BuildJobState();
    List<BuildJobStateFileHashes> fileHashes = new ArrayList<>();
    buildJobState.setFileHashes(fileHashes);
    BuildJobStateTargetGraph graph = new BuildJobStateTargetGraph();
    graph.setNodes(new ArrayList<BuildJobStateTargetNode>());
    BuildJobStateTargetNode node1 = new BuildJobStateTargetNode();
    node1.setRawNode("node1");
    BuildJobStateTargetNode node2 = new BuildJobStateTargetNode();
    node2.setRawNode("node1");
    graph.addToNodes(node1);
    graph.addToNodes(node2);
    buildJobState.setTargetGraph(graph);
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("check-id");
    distBuildService.uploadTargetGraph(buildJobState, stampedeId, distBuildClientStatsTracker);

    EasyMock.verify(frontendService);

    Assert.assertTrue(request.getValue().isSetType());
    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.STORE_BUILD_GRAPH);
    Assert.assertTrue(request.getValue().isSetStoreBuildGraphRequest());
    Assert.assertTrue(request.getValue().getStoreBuildGraphRequest().isSetStampedeId());
    Assert.assertEquals(request.getValue().getStoreBuildGraphRequest().getStampedeId(), stampedeId);
    Assert.assertTrue(request.getValue().getStoreBuildGraphRequest().isSetBuildGraph());

    BuildJobState sentState =
        BuildJobStateSerializer.deserialize(
            request.getValue().getStoreBuildGraphRequest().getBuildGraph());
    Assert.assertTrue(buildJobState.equals(sentState));
  }

  @Test
  public void canUploadFiles() throws Exception {
    List<Boolean> fileExistence = Arrays.asList(true, false, true);

    Capture<FrontendRequest> containsRequest = EasyMock.newCapture();
    FrontendResponse containsResponse = new FrontendResponse();
    containsResponse.setType(FrontendRequestType.CAS_CONTAINS);
    CASContainsResponse casContainsResponse = new CASContainsResponse();
    casContainsResponse.setExists(fileExistence);
    containsResponse.setCasContainsResponse(casContainsResponse);
    containsResponse.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(containsRequest)))
        .andReturn(containsResponse)
        .once();

    Capture<FrontendRequest> storeRequest = EasyMock.newCapture();
    FrontendResponse storeResponse = new FrontendResponse();
    storeResponse.setType(FrontendRequestType.STORE_LOCAL_CHANGES);
    storeResponse.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(storeRequest)))
        .andReturn(storeResponse)
        .once();

    EasyMock.replay(frontendService);

    BuildJobStateFileHashEntry files[] = new BuildJobStateFileHashEntry[3];
    Map<Integer, ProjectFilesystem> filesystems = new HashMap<>();
    for (int i = 0; i < 2; i++) {
      filesystems.put(i, new FakeProjectFilesystem());
    }
    for (int i = 0; i < 3; i++) {
      Path path = temporaryFolder.newFile().toAbsolutePath();
      String content = "content" + i;
      Files.write(path, content.getBytes(StandardCharsets.UTF_8));
      filesystems.get(i / 2).writeContentsToPath(content, Paths.get(path.toString()));

      files[i] = new BuildJobStateFileHashEntry();
      files[i].setSha1(Integer.toString(i));
      files[i].setPath(new PathWithUnixSeparators().setPath(path.toString()));
    }

    List<BuildJobStateFileHashes> fileHashes = new ArrayList<>();
    fileHashes.add(new BuildJobStateFileHashes());
    fileHashes.get(0).setCellIndex(0);
    fileHashes.get(0).setEntries(new ArrayList<>());
    fileHashes.get(0).getEntries().add(files[0]);
    fileHashes.get(0).getEntries().add(files[1]);
    fileHashes.add(new BuildJobStateFileHashes());
    fileHashes.get(1).setCellIndex(1);
    fileHashes.get(1).setEntries(new ArrayList<>());
    fileHashes.get(1).getEntries().add(files[2]);

    distBuildService
        .uploadMissingFilesAsync(filesystems, fileHashes, distBuildClientStatsTracker, executor)
        .get();

    EasyMock.verify(frontendService);

    Assert.assertEquals(containsRequest.getValue().getType(), FrontendRequestType.CAS_CONTAINS);
    Assert.assertTrue(containsRequest.getValue().isSetCasContainsRequest());
    Assert.assertTrue(containsRequest.getValue().getCasContainsRequest().isSetContentSha1s());
    Assert.assertEquals(
        new HashSet<String>(containsRequest.getValue().getCasContainsRequest().getContentSha1s()),
        new HashSet<String>(Arrays.asList("0", "1", "2")));

    Assert.assertEquals(storeRequest.getValue().getType(), FrontendRequestType.STORE_LOCAL_CHANGES);
    Assert.assertTrue(storeRequest.getValue().isSetStoreLocalChangesRequest());
    Assert.assertTrue(storeRequest.getValue().getStoreLocalChangesRequest().isSetFiles());
    Assert.assertEquals(storeRequest.getValue().getStoreLocalChangesRequest().getFiles().size(), 1);
    Assert.assertEquals(
        storeRequest.getValue().getStoreLocalChangesRequest().getFiles().get(0).getContentHash(),
        "1");
    Assert.assertTrue(
        Arrays.equals(
            storeRequest.getValue().getStoreLocalChangesRequest().getFiles().get(0).getContent(),
            "content1".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void canCreateBuild() throws Exception {
    final String idString = "create id";

    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.CREATE_BUILD);
    CreateBuildResponse createBuildResponse = new CreateBuildResponse();
    BuildJob buildJob = new BuildJob();
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(idString);
    buildJob.setStampedeId(stampedeId);
    createBuildResponse.setBuildJob(buildJob);
    createBuildResponse.setWasAccepted(true);
    response.setCreateBuildResponse(createBuildResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    BuildJob job =
        distBuildService.createBuild(
            new BuildId("33-44"),
            BuildMode.REMOTE_BUILD,
            new MinionRequirements(),
            REPOSITORY,
            TENANT_ID,
            BUILD_TARGETS,
            BUILD_LABEL);

    EasyMock.verify(frontendService);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.CREATE_BUILD);
    Assert.assertTrue(request.getValue().isSetCreateBuildRequest());
    Assert.assertTrue(request.getValue().getCreateBuildRequest().isSetCreateTimestampMillis());
    Assert.assertTrue(request.getValue().getCreateBuildRequest().isSetUsername());

    Assert.assertTrue(job.isSetStampedeId());
    Assert.assertTrue(job.getStampedeId().isSetId());
    Assert.assertEquals(job.getStampedeId().getId(), idString);
  }

  @Test
  public void canStartBuild() throws Exception {
    final String idString = "start id";

    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.START_BUILD);
    StartBuildResponse startBuildResponse = new StartBuildResponse();
    BuildJob buildJob = new BuildJob();
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(idString);
    buildJob.setStampedeId(stampedeId);
    startBuildResponse.setBuildJob(buildJob);
    response.setStartBuildResponse(startBuildResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    StampedeId id = new StampedeId();
    id.setId(idString);
    BuildJob job = distBuildService.startBuild(id);

    EasyMock.verify(frontendService);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.START_BUILD);
    Assert.assertTrue(request.getValue().isSetStartBuildRequest());
    Assert.assertTrue(request.getValue().getStartBuildRequest().isSetStampedeId());
    Assert.assertEquals(request.getValue().getStartBuildRequest().getStampedeId(), id);

    Assert.assertTrue(job.isSetStampedeId());
    Assert.assertEquals(job.getStampedeId(), id);
  }

  @Test
  public void canPollBuild() throws Exception {
    final String idString = "poll id";

    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.BUILD_STATUS);
    BuildStatusResponse buildStatusResponse = new BuildStatusResponse();
    BuildJob buildJob = new BuildJob();
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(idString);
    buildJob.setStampedeId(stampedeId);
    buildStatusResponse.setBuildJob(buildJob);
    response.setBuildStatusResponse(buildStatusResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    StampedeId id = new StampedeId();
    id.setId(idString);
    BuildJob job = distBuildService.getCurrentBuildJobState(id);

    EasyMock.verify(frontendService);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.BUILD_STATUS);
    Assert.assertTrue(request.getValue().isSetBuildStatusRequest());
    Assert.assertTrue(request.getValue().getBuildStatusRequest().isSetStampedeId());
    Assert.assertEquals(request.getValue().getBuildStatusRequest().getStampedeId(), id);

    Assert.assertTrue(job.isSetStampedeId());
    Assert.assertEquals(job.getStampedeId(), id);
  }

  @Test
  public void canFetchSourceFiles() throws IOException {
    ImmutableList<String> hashCodes = ImmutableList.of("a", "b", "c");
    ImmutableList<String> fileContents = ImmutableList.of("1", "2", "3");
    List<FileInfo> fileInfo = new ArrayList<>(hashCodes.size());
    for (int i = 0; i < hashCodes.size(); ++i) {
      FileInfo f = new FileInfo();
      f.setContentHash(hashCodes.get(i));
      f.setContent(fileContents.get(i).getBytes(StandardCharsets.UTF_8));
      fileInfo.add(f);
    }

    FrontendRequest expectedRequest =
        new FrontendRequest()
            .setType(FrontendRequestType.FETCH_SRC_FILES)
            .setFetchSourceFilesRequest(new FetchSourceFilesRequest().setContentHashes(hashCodes));
    FrontendResponse response =
        new FrontendResponse()
            .setType(FrontendRequestType.FETCH_SRC_FILES)
            .setWasSuccessful(true)
            .setFetchSourceFilesResponse(new FetchSourceFilesResponse().setFiles(fileInfo));
    EasyMock.expect(frontendService.makeRequest(expectedRequest)).andReturn(response).once();

    EasyMock.replay(frontendService);

    ImmutableMap<String, byte[]> result =
        distBuildService.multiFetchSourceFiles(ImmutableSet.copyOf(hashCodes));

    EasyMock.verify(frontendService);

    Assert.assertEquals(hashCodes.size(), result.keySet().size());
    for (int i = 0; i < hashCodes.size(); ++i) {
      String hashCode = hashCodes.get(i);
      String content = fileContents.get(i);

      Assert.assertTrue(result.containsKey(hashCode));
      Assert.assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result.get(hashCode));
    }
  }

  @Test
  public void canTransmitConsoleEvents() throws IOException {
    // Check that uploadBuildSlaveConsoleEvents sends a valid thing,
    // and then use that capture to reply to a multiGetBuildSlaveEvents request (using `andAnswer`),
    // and finally verify that the events are the same.

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("super");
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("duper");

    ImmutableList<BuildSlaveEvent> consoleEvents =
        ImmutableList.of(
            DistBuildUtil.createBuildSlaveConsoleEvent(1),
            DistBuildUtil.createBuildSlaveConsoleEvent(2),
            DistBuildUtil.createBuildSlaveConsoleEvent(3));
    consoleEvents.get(0).getConsoleEvent().setMessage("a");
    consoleEvents.get(1).getConsoleEvent().setMessage("b");
    consoleEvents.get(2).getConsoleEvent().setMessage("c");

    Capture<FrontendRequest> request1 = EasyMock.newCapture();
    Capture<FrontendRequest> request2 = EasyMock.newCapture();

    FrontendResponse response = new FrontendResponse();
    response.setWasSuccessful(true);
    response.setType(FrontendRequestType.APPEND_BUILD_SLAVE_EVENTS);
    response.setAppendBuildSlaveEventsResponse(new AppendBuildSlaveEventsResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request1)))
        .andReturn(response)
        .once();
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request2)))
        .andAnswer(
            () -> {
              List<ByteBuffer> receivedBinaryEvents =
                  request1.getValue().getAppendBuildSlaveEventsRequest().getEvents();
              List<SequencedBuildSlaveEvent> sequencedEvents =
                  IntStream.range(0, 3)
                      .mapToObj(
                          i -> {
                            SequencedBuildSlaveEvent slaveEvent = new SequencedBuildSlaveEvent();
                            slaveEvent.setEventNumber(i);
                            slaveEvent.setEvent(receivedBinaryEvents.get(i));
                            return slaveEvent;
                          })
                      .collect(Collectors.toList());

              BuildSlaveEventsQuery query =
                  distBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 2);
              BuildSlaveEventsRange eventsRange = new BuildSlaveEventsRange();
              eventsRange.setSuccess(true);
              eventsRange.setQuery(query);
              eventsRange.setEvents(sequencedEvents);

              MultiGetBuildSlaveEventsResponse eventsResponse =
                  new MultiGetBuildSlaveEventsResponse();
              eventsResponse.addToResponses(eventsRange);
              FrontendResponse response2 = new FrontendResponse();
              response2.setWasSuccessful(true);
              response2.setType(FrontendRequestType.MULTI_GET_BUILD_SLAVE_EVENTS);
              response2.setMultiGetBuildSlaveEventsResponse(eventsResponse);
              return response2;
            })
        .once();
    EasyMock.replay(frontendService);

    distBuildService.uploadBuildSlaveEvents(stampedeId, buildSlaveRunId, consoleEvents);
    BuildSlaveEventsQuery query =
        distBuildService.createBuildSlaveEventsQuery(stampedeId, buildSlaveRunId, 2);
    List<BuildSlaveEventWrapper> events =
        distBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query));

    EasyMock.verify(frontendService);

    // Verify correct events are received.
    Assert.assertEquals(events.size(), 3);
    Assert.assertEquals(events.get(0).getEventNumber(), 0);
    Assert.assertEquals(events.get(1).getEventNumber(), 1);
    Assert.assertEquals(events.get(2).getEventNumber(), 2);
    for (int i = 0; i < 3; ++i) {
      BuildSlaveEventWrapper wrapper = events.get(i);
      Assert.assertEquals(wrapper.getEvent().getEventType(), BuildSlaveEventType.CONSOLE_EVENT);
      Assert.assertEquals(wrapper.getBuildSlaveRunId(), buildSlaveRunId);
      Assert.assertEquals(wrapper.getEvent(), consoleEvents.get(i));
    }

    // Verify validity of first request.
    FrontendRequest receivedRequest = request1.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(receivedRequest.getType(), FrontendRequestType.APPEND_BUILD_SLAVE_EVENTS);
    Assert.assertTrue(receivedRequest.isSetAppendBuildSlaveEventsRequest());

    AppendBuildSlaveEventsRequest appendRequest =
        receivedRequest.getAppendBuildSlaveEventsRequest();
    Assert.assertTrue(appendRequest.isSetStampedeId());
    Assert.assertEquals(appendRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(appendRequest.isSetBuildSlaveRunId());
    Assert.assertEquals(appendRequest.getBuildSlaveRunId(), buildSlaveRunId);
    Assert.assertTrue(appendRequest.isSetEvents());
    Assert.assertEquals(appendRequest.getEvents().size(), 3);

    // Verify validity of second request.
    receivedRequest = request2.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(
        receivedRequest.getType(), FrontendRequestType.MULTI_GET_BUILD_SLAVE_EVENTS);
    Assert.assertTrue(receivedRequest.isSetMultiGetBuildSlaveEventsRequest());

    MultiGetBuildSlaveEventsRequest eventsRequest =
        receivedRequest.getMultiGetBuildSlaveEventsRequest();
    Assert.assertTrue(eventsRequest.isSetRequests());
    Assert.assertEquals(eventsRequest.getRequests().size(), 1);

    BuildSlaveEventsQuery receivedQuery = eventsRequest.getRequests().get(0);
    Assert.assertTrue(receivedQuery.isSetStampedeId());
    Assert.assertEquals(receivedQuery.getStampedeId(), stampedeId);
    Assert.assertTrue(receivedQuery.isSetBuildSlaveRunId());
    Assert.assertEquals(receivedQuery.getBuildSlaveRunId(), buildSlaveRunId);
    Assert.assertTrue(receivedQuery.isSetFirstEventNumber());
    Assert.assertEquals(receivedQuery.getFirstEventNumber(), 2);
  }

  @Test
  public void canTransmitSlaveStatus() throws IOException {
    // Check that updateBuildSlaveStatus sends a valid thing,
    // and then use that capture to reply to a fetchBuildSlaveStatusRequest request,
    // and finally verify that the statuses is the same.

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("super");
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("duper");

    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setBuildSlaveRunId(buildSlaveRunId);
    slaveStatus.setTotalRulesCount(123);

    Capture<FrontendRequest> request1 = EasyMock.newCapture();
    Capture<FrontendRequest> request2 = EasyMock.newCapture();

    FrontendResponse response = new FrontendResponse();
    response.setWasSuccessful(true);
    response.setType(FrontendRequestType.UPDATE_BUILD_SLAVE_STATUS);
    response.setUpdateBuildSlaveStatusResponse(new UpdateBuildSlaveStatusResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request1)))
        .andReturn(response)
        .once();
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request2)))
        .andAnswer(
            () -> {
              FetchBuildSlaveStatusResponse statusResponse = new FetchBuildSlaveStatusResponse();
              statusResponse.setBuildSlaveStatus(
                  request1.getValue().getUpdateBuildSlaveStatusRequest().getBuildSlaveStatus());

              FrontendResponse response2 = new FrontendResponse();
              response2.setWasSuccessful(true);
              response2.setType(FrontendRequestType.FETCH_BUILD_SLAVE_STATUS);
              response2.setFetchBuildSlaveStatusResponse(statusResponse);
              return response2;
            })
        .once();
    EasyMock.replay(frontendService);

    distBuildService.updateBuildSlaveStatus(stampedeId, buildSlaveRunId, slaveStatus);
    BuildSlaveStatus receivedStatus =
        distBuildService.fetchBuildSlaveStatus(stampedeId, buildSlaveRunId).get();

    EasyMock.verify(frontendService);
    Assert.assertEquals(receivedStatus, slaveStatus);

    // Verify validity of first request.
    FrontendRequest receivedRequest = request1.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(receivedRequest.getType(), FrontendRequestType.UPDATE_BUILD_SLAVE_STATUS);
    Assert.assertTrue(receivedRequest.isSetUpdateBuildSlaveStatusRequest());

    UpdateBuildSlaveStatusRequest updateRequest =
        receivedRequest.getUpdateBuildSlaveStatusRequest();
    Assert.assertTrue(updateRequest.isSetStampedeId());
    Assert.assertEquals(updateRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(updateRequest.isSetBuildSlaveRunId());
    Assert.assertEquals(updateRequest.getBuildSlaveRunId(), buildSlaveRunId);
    Assert.assertTrue(updateRequest.isSetBuildSlaveStatus());

    // Verify validity of second request.
    receivedRequest = request2.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(receivedRequest.getType(), FrontendRequestType.FETCH_BUILD_SLAVE_STATUS);
    Assert.assertTrue(receivedRequest.isSetFetchBuildSlaveStatusRequest());

    FetchBuildSlaveStatusRequest statusRequest = receivedRequest.getFetchBuildSlaveStatusRequest();
    Assert.assertTrue(statusRequest.isSetStampedeId());
    Assert.assertEquals(statusRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(statusRequest.isSetBuildSlaveRunId());
    Assert.assertEquals(statusRequest.getBuildSlaveRunId(), buildSlaveRunId);
  }

  @Test
  public void canTransmitSlaveFinishedStats() throws IOException {
    // Check that storeBuildSlaveFinishedStats sends a valid thing,
    // and then use that capture to reply to a fetchBuildSlaveFinishedStatsRequest request,
    // and finally verify that the stats object is the same.

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("super");
    BuildSlaveRunId runId = new BuildSlaveRunId();
    runId.setId("duper");

    BuildSlaveFinishedStats slaveFinishedStats = new BuildSlaveFinishedStats();
    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setBuildSlaveRunId(runId);
    slaveStatus.setTotalRulesCount(123);
    slaveFinishedStats.setBuildSlaveStatus(slaveStatus);
    slaveFinishedStats.setExitCode(42);

    Capture<FrontendRequest> request1 = EasyMock.newCapture();
    Capture<FrontendRequest> request2 = EasyMock.newCapture();

    FrontendResponse response = new FrontendResponse();
    response.setWasSuccessful(true);
    response.setType(FrontendRequestType.STORE_BUILD_SLAVE_FINISHED_STATS);
    response.setStoreBuildSlaveFinishedStatsResponse(new StoreBuildSlaveFinishedStatsResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request1)))
        .andReturn(response)
        .once();
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request2)))
        .andAnswer(
            () -> {
              FetchBuildSlaveFinishedStatsResponse statsResponse =
                  new FetchBuildSlaveFinishedStatsResponse();
              statsResponse.setBuildSlaveFinishedStats(
                  request1
                      .getValue()
                      .getStoreBuildSlaveFinishedStatsRequest()
                      .getBuildSlaveFinishedStats());

              FrontendResponse response2 = new FrontendResponse();
              response2.setWasSuccessful(true);
              response2.setType(FrontendRequestType.FETCH_BUILD_SLAVE_FINISHED_STATS);
              response2.setFetchBuildSlaveFinishedStatsResponse(statsResponse);
              return response2;
            })
        .once();
    EasyMock.replay(frontendService);

    distBuildService.storeBuildSlaveFinishedStats(stampedeId, runId, slaveFinishedStats);
    BuildSlaveFinishedStats receivedStats =
        distBuildService.fetchBuildSlaveFinishedStats(stampedeId, runId).get();
    Assert.assertEquals(receivedStats, slaveFinishedStats);

    EasyMock.verify(frontendService);

    // Verify validity of first request.
    FrontendRequest receivedRequest = request1.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(
        receivedRequest.getType(), FrontendRequestType.STORE_BUILD_SLAVE_FINISHED_STATS);
    Assert.assertTrue(receivedRequest.isSetStoreBuildSlaveFinishedStatsRequest());

    StoreBuildSlaveFinishedStatsRequest storeRequest =
        receivedRequest.getStoreBuildSlaveFinishedStatsRequest();
    Assert.assertTrue(storeRequest.isSetStampedeId());
    Assert.assertEquals(storeRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(storeRequest.isSetBuildSlaveRunId());
    Assert.assertEquals(storeRequest.getBuildSlaveRunId(), runId);
    Assert.assertTrue(storeRequest.isSetBuildSlaveFinishedStats());

    // Verify validity of second request.
    receivedRequest = request2.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(
        receivedRequest.getType(), FrontendRequestType.FETCH_BUILD_SLAVE_FINISHED_STATS);
    Assert.assertTrue(receivedRequest.isSetFetchBuildSlaveFinishedStatsRequest());

    FetchBuildSlaveFinishedStatsRequest statsRequest =
        receivedRequest.getFetchBuildSlaveFinishedStatsRequest();
    Assert.assertTrue(statsRequest.isSetStampedeId());
    Assert.assertEquals(statsRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(statsRequest.isSetBuildSlaveRunId());
    Assert.assertEquals(statsRequest.getBuildSlaveRunId(), runId);
  }

  @Test
  public void testRequestContainsStampedeId() {
    StampedeId stampedeId = createStampedeId("topspin");
    FrontendRequest request = DistBuildService.createFrontendBuildStatusRequest(stampedeId);
    Assert.assertEquals(stampedeId, request.getBuildStatusRequest().getStampedeId());
  }

  private static StampedeId createStampedeId(String id) {
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(id);
    return stampedeId;
  }

  @Test
  public void testSetCoordinator() throws IOException {
    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response =
        new FrontendResponse()
            .setWasSuccessful(true)
            .setType(FrontendRequestType.SET_COORDINATOR)
            .setSetCoordinatorResponse(new SetCoordinatorResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    StampedeId stampedeId = createStampedeId("super kewl");
    int port = 4284;
    String address = "very nice address indeed";
    distBuildService.setCoordinator(stampedeId, port, address);
    EasyMock.verify(frontendService);
    Assert.assertEquals(FrontendRequestType.SET_COORDINATOR, request.getValue().getType());
    SetCoordinatorRequest coordinatorRequest = request.getValue().getSetCoordinatorRequest();
    Assert.assertEquals(stampedeId, coordinatorRequest.getStampedeId());
    Assert.assertEquals(port, coordinatorRequest.getCoordinatorPort());
    Assert.assertEquals(address, coordinatorRequest.getCoordinatorHostname());
  }

  @Test
  public void testEnqueueMinions() throws IOException {
    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response =
        new FrontendResponse()
            .setWasSuccessful(true)
            .setType(FrontendRequestType.ENQUEUE_MINIONS)
            .setEnqueueMinionsResponse(new EnqueueMinionsResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    StampedeId stampedeId = createStampedeId("minions, here I come");
    String buildLabel = "behold-my-glory";
    int minionCount = 21;
    String minionQueueName = "a_happy_place_indeed";
    String minionRegion = "true_happy_place";
    distBuildService.enqueueMinions(
        stampedeId,
        buildLabel,
        minionCount,
        minionQueueName,
        MinionType.STANDARD_SPEC,
        minionRegion);

    EasyMock.verify(frontendService);
    Assert.assertEquals(FrontendRequestType.ENQUEUE_MINIONS, request.getValue().getType());
    EnqueueMinionsRequest minionsRequest = request.getValue().getEnqueueMinionsRequest();
    Assert.assertEquals(stampedeId, minionsRequest.getStampedeId());
    Assert.assertEquals(buildLabel, minionsRequest.getBuildLabel());
    Assert.assertEquals(minionCount, minionsRequest.getNumberOfMinions());
    Assert.assertEquals(minionQueueName, minionsRequest.getMinionQueue());
  }

  @Test
  public void testSetFinalBuildStatus() throws IOException {
    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response =
        new FrontendResponse()
            .setWasSuccessful(true)
            .setType(FrontendRequestType.SET_FINAL_BUILD_STATUS)
            .setSetFinalBuildStatusResponse(new SetFinalBuildStatusResponse());
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    StampedeId stampedeId = createStampedeId("coordinator has decided to set the final status");
    BuildStatus finalStatus = BuildStatus.FINISHED_SUCCESSFULLY;
    String finalStatusMessage = "Super cool message!!!!";
    distBuildService.setFinalBuildStatus(stampedeId, finalStatus, finalStatusMessage);

    EasyMock.verify(frontendService);
    Assert.assertEquals(FrontendRequestType.SET_FINAL_BUILD_STATUS, request.getValue().getType());
    SetFinalBuildStatusRequest setStatusRequest =
        request.getValue().getSetFinalBuildStatusRequest();
    Assert.assertEquals(stampedeId, setStatusRequest.getStampedeId());
    Assert.assertEquals(finalStatus, setStatusRequest.getBuildStatus());
    Assert.assertEquals(finalStatusMessage, setStatusRequest.getBuildStatusMessage());
  }
}
