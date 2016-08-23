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

import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildStatusResponse;
import com.facebook.buck.distributed.thrift.CASContainsResponse;
import com.facebook.buck.distributed.thrift.CreateBuildResponse;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.StartBuildResponse;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;

public class DistBuildServiceTest {
  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private FrontendService frontendService;
  private DistBuildService distBuildService;
  private ListeningExecutorService executor;

  @Before
  public void setUp() throws IOException, InterruptedException {
    frontendService = EasyMock.createStrictMock(FrontendService.class);
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    distBuildService = new DistBuildService(frontendService);
  }

  @After
  public void tearDown() {
    executor.shutdown();
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
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(containsRequest))).andReturn(
        containsResponse).once();

    Capture<FrontendRequest> storeRequest = EasyMock.newCapture();
    FrontendResponse storeResponse = new FrontendResponse();
    storeResponse.setType(FrontendRequestType.STORE_LOCAL_CHANGES);
    storeResponse.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(storeRequest))).andReturn(
        storeResponse).once();

    EasyMock.replay(frontendService);

    BuildJobStateFileHashEntry files[] = new BuildJobStateFileHashEntry[3];
    for (int i = 0; i < 3; i++) {
      files[i] = new BuildJobStateFileHashEntry();
      files[i].setHashCode(Integer.toString(i));
      files[i].setContents(("content" + Integer.toString(i)).getBytes());
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
    distBuildService.uploadMissingFiles(fileHashes, executor).get();

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
    Assert.assertTrue(Arrays.equals(
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
    BuildId buildId = new BuildId();
    buildId.setId(idString);
    buildJob.setBuildId(buildId);
    createBuildResponse.setBuildJob(buildJob);
    response.setCreateBuildResponse(createBuildResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response).once();
    EasyMock.replay(frontendService);

    BuildJob job = distBuildService.createBuild();

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.CREATE_BUILD);
    Assert.assertTrue(request.getValue().isSetCreateBuildRequest());
    Assert.assertTrue(request.getValue().getCreateBuildRequest().isSetCreateTimestampMillis());

    Assert.assertTrue(job.isSetBuildId());
    Assert.assertTrue(job.getBuildId().isSetId());
    Assert.assertEquals(job.getBuildId().getId(), idString);
  }

  @Test
  public void canStartBuild() throws Exception {
    final String idString = "start id";

    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.START_BUILD);
    StartBuildResponse startBuildResponse = new StartBuildResponse();
    BuildJob buildJob = new BuildJob();
    BuildId buildId = new BuildId();
    buildId.setId(idString);
    buildJob.setBuildId(buildId);
    startBuildResponse.setBuildJob(buildJob);
    response.setStartBuildResponse(startBuildResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response).once();
    EasyMock.replay(frontendService);

    BuildId id = new BuildId();
    id.setId(idString);
    BuildJob job = distBuildService.startBuild(id);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.START_BUILD);
    Assert.assertTrue(request.getValue().isSetStartBuildRequest());
    Assert.assertTrue(request.getValue().getStartBuildRequest().isSetBuildId());
    Assert.assertEquals(request.getValue().getStartBuildRequest().getBuildId(), id);

    Assert.assertTrue(job.isSetBuildId());
    Assert.assertEquals(job.getBuildId(), id);
  }

  @Test
  public void canPollBuild() throws Exception {
    final String idString = "poll id";

    Capture<FrontendRequest> request = EasyMock.newCapture();
    FrontendResponse response = new FrontendResponse();
    response.setType(FrontendRequestType.BUILD_STATUS);
    BuildStatusResponse buildStatusResponse = new BuildStatusResponse();
    BuildJob buildJob = new BuildJob();
    BuildId buildId = new BuildId();
    buildId.setId(idString);
    buildJob.setBuildId(buildId);
    buildStatusResponse.setBuildJob(buildJob);
    response.setBuildStatusResponse(buildStatusResponse);
    response.setWasSuccessful(true);
    EasyMock.expect(frontendService.makeRequest(EasyMock.capture(request)))
        .andReturn(response).once();
    EasyMock.replay(frontendService);

    BuildId id = new BuildId();
    id.setId(idString);
    BuildJob job = distBuildService.pollBuild(id);

    Assert.assertEquals(request.getValue().getType(), FrontendRequestType.BUILD_STATUS);
    Assert.assertTrue(request.getValue().isSetBuildStatusRequest());
    Assert.assertTrue(request.getValue().getBuildStatusRequest().isSetBuildId());
    Assert.assertEquals(request.getValue().getBuildStatusRequest().getBuildId(), id);

    Assert.assertTrue(job.isSetBuildId());
    Assert.assertEquals(job.getBuildId(), id);
  }
}
