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

import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsResponse;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsRange;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatusResponse;
import com.facebook.buck.distributed.thrift.CASContainsResponse;
import com.facebook.buck.distributed.thrift.CreateBuildResponse;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusResponse;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsResponse;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.SequencedBuildSlaveEvent;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StartBuildResponse;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusResponse;
import com.facebook.buck.model.Pair;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
  private DistBuildClientStatsTracker distBuildClientStatsTracker;

  @Before
  public void setUp() throws IOException, InterruptedException {
    frontendService = EasyMock.createStrictMock(FrontendService.class);
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    distBuildService = new DistBuildService(frontendService);
    distBuildClientStatsTracker = new DistBuildClientStatsTracker();
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void canUploadTargetGraph() throws IOException, ExecutionException, InterruptedException {
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
    final List<Boolean> fileExistence = Arrays.asList(true, false, true);

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
    for (int i = 0; i < 3; i++) {
      files[i] = new BuildJobStateFileHashEntry();
      files[i].setHashCode(Integer.toString(i));
      files[i].setContents(("content" + Integer.toString(i)).getBytes());
      files[i].setPath(new PathWithUnixSeparators().setPath("/tmp/" + i));
    }

    List<BuildJobStateFileHashes> fileHashes = new ArrayList<>();
    fileHashes.add(new BuildJobStateFileHashes());
    fileHashes.get(0).setCellIndex(0);
    fileHashes.get(0).setEntries(new ArrayList<BuildJobStateFileHashEntry>());
    fileHashes.get(0).getEntries().add(files[0]);
    fileHashes.get(0).getEntries().add(files[1]);
    fileHashes.add(new BuildJobStateFileHashes());
    fileHashes.get(1).setCellIndex(1);
    fileHashes.get(1).setEntries(new ArrayList<BuildJobStateFileHashEntry>());
    fileHashes.get(1).getEntries().add(files[2]);
    distBuildService
        .uploadMissingFilesAsync(fileHashes, distBuildClientStatsTracker, executor)
        .get();

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
            "content1".getBytes()));
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
    response.setCreateBuildResponse(createBuildResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response)
        .once();
    EasyMock.replay(frontendService);

    BuildJob job = distBuildService.createBuild(BuildMode.REMOTE_BUILD, 1);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.CREATE_BUILD);
    Assert.assertTrue(request.getValue().isSetCreateBuildRequest());
    Assert.assertTrue(request.getValue().getCreateBuildRequest().isSetCreateTimestampMillis());

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

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.BUILD_STATUS);
    Assert.assertTrue(request.getValue().isSetBuildStatusRequest());
    Assert.assertTrue(request.getValue().getBuildStatusRequest().isSetStampedeId());
    Assert.assertEquals(request.getValue().getBuildStatusRequest().getStampedeId(), id);

    Assert.assertTrue(job.isSetStampedeId());
    Assert.assertEquals(job.getStampedeId(), id);
  }

  @Test
  public void canTransmitConsoleEvents() throws IOException {
    // Check that uploadBuildSlaveConsoleEvents sends a valid thing,
    // and then use that capture to reply to a multiGetBuildSlaveEvents request (using `andAnswer`),
    // and finally verify that the events are the same.

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("super");
    RunId runId = new RunId();
    runId.setId("duper");

    ImmutableList<BuildSlaveConsoleEvent> consoleEvents =
        ImmutableList.of(
            new BuildSlaveConsoleEvent(),
            new BuildSlaveConsoleEvent(),
            new BuildSlaveConsoleEvent());
    consoleEvents.get(0).setMessage("a");
    consoleEvents.get(1).setMessage("b");
    consoleEvents.get(2).setMessage("c");

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
                  distBuildService.createBuildSlaveEventsQuery(stampedeId, runId, 2);
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

    distBuildService.uploadBuildSlaveConsoleEvents(stampedeId, runId, consoleEvents);
    BuildSlaveEventsQuery query =
        distBuildService.createBuildSlaveEventsQuery(stampedeId, runId, 2);
    List<Pair<Integer, BuildSlaveEvent>> events =
        distBuildService.multiGetBuildSlaveEvents(ImmutableList.of(query));

    // Verify correct events are received.
    Assert.assertEquals(events.size(), 3);
    Assert.assertEquals((int) events.get(0).getFirst(), 0);
    Assert.assertEquals((int) events.get(1).getFirst(), 1);
    Assert.assertEquals((int) events.get(2).getFirst(), 2);
    List<BuildSlaveEvent> buildSlaveEvents =
        events.stream().map(x -> x.getSecond()).collect(Collectors.toList());
    for (int i = 0; i < 3; ++i) {
      BuildSlaveEvent event = buildSlaveEvents.get(i);
      Assert.assertEquals(event.getEventType(), BuildSlaveEventType.CONSOLE_EVENT);
      Assert.assertEquals(event.getStampedeId(), stampedeId);
      Assert.assertEquals(event.getRunId(), runId);
      Assert.assertEquals(event.getConsoleEvent(), consoleEvents.get(i));
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
    Assert.assertTrue(appendRequest.isSetRunId());
    Assert.assertEquals(appendRequest.getRunId(), runId);
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
    Assert.assertTrue(receivedQuery.isSetRunId());
    Assert.assertEquals(receivedQuery.getRunId(), runId);
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
    RunId runId = new RunId();
    runId.setId("duper");

    BuildSlaveStatus slaveStatus = new BuildSlaveStatus();
    slaveStatus.setStampedeId(stampedeId);
    slaveStatus.setRunId(runId);
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

    distBuildService.updateBuildSlaveStatus(stampedeId, runId, slaveStatus);
    BuildSlaveStatus receivedStatus =
        distBuildService.fetchBuildSlaveStatus(stampedeId, runId).get();
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
    Assert.assertTrue(updateRequest.isSetRunId());
    Assert.assertEquals(updateRequest.getRunId(), runId);
    Assert.assertTrue(updateRequest.isSetBuildSlaveStatus());

    // Verify validity of second request.
    receivedRequest = request2.getValue();
    Assert.assertTrue(receivedRequest.isSetType());
    Assert.assertEquals(receivedRequest.getType(), FrontendRequestType.FETCH_BUILD_SLAVE_STATUS);
    Assert.assertTrue(receivedRequest.isSetFetchBuildSlaveStatusRequest());

    FetchBuildSlaveStatusRequest statusRequest = receivedRequest.getFetchBuildSlaveStatusRequest();
    Assert.assertTrue(statusRequest.isSetStampedeId());
    Assert.assertEquals(statusRequest.getStampedeId(), stampedeId);
    Assert.assertTrue(statusRequest.isSetRunId());
    Assert.assertEquals(statusRequest.getRunId(), runId);
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
}
