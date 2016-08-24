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
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.CASContainsRequest;
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.distributed.thrift.StoreBuildGraphRequest;
import com.facebook.buck.distributed.thrift.StoreLocalChangesRequest;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;


public class DistBuildService {
  private final FrontendService service;
  private static final ThriftProtocol buildGraphSerializerProtocol = ThriftProtocol.BINARY;

  public DistBuildService(
      FrontendService service) {
    this.service = service;
  }

  private FrontendResponse makeRequestChecked(FrontendRequest request) throws IOException {
    FrontendResponse response = service.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(String.format(
          "Request of type [%s] failed with error message [%s]",
          request.type.toString(),
          response.getErrorMessage()));
    }
    Preconditions.checkState(request.getType().equals(response.getType()));
    return response;
  }

  public ListenableFuture<Void> uploadTargetGraph(
      final BuildJobState buildJobStateArg,
      final BuildId buildId,
      ListeningExecutorService executorService) throws IOException {
    // TODO(shivanker): We shouldn't be doing this. Fix after we stop reading all files into memory.
    final BuildJobState buildJobState = buildJobStateArg.deepCopy();
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws IOException {
        // Get rid of file contents from buildJobState
        for (BuildJobStateFileHashes cell : buildJobState.getFileHashes()) {
          for (BuildJobStateFileHashEntry file : cell.getEntries()) {
            file.unsetContents();
          }
        }

        // Now serialize and send the whole buildJobState
        StoreBuildGraphRequest storeBuildGraphRequest = new StoreBuildGraphRequest();
        storeBuildGraphRequest.setBuildId(buildId);
        storeBuildGraphRequest.setBuildGraph(
            ThriftUtil.serialize(buildGraphSerializerProtocol, buildJobState));

        FrontendRequest request = new FrontendRequest();
        request.setType(FrontendRequestType.STORE_BUILD_GRAPH);
        request.setStoreBuildGraphRequest(storeBuildGraphRequest);
        makeRequestChecked(request);
        // No response expected.
        return null;
      }
    });
  }

  public ListenableFuture<Void> uploadMissingFiles(
      final List<BuildJobStateFileHashes> fileHashes,
      ListeningExecutorService executorService) throws IOException {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws IOException {
        Map<String, ByteBuffer> sha1ToFileContents = new HashMap<>();
        for (BuildJobStateFileHashes filesystem : fileHashes) {
          for (BuildJobStateFileHashEntry file : filesystem.entries) {
            // TODO(shivanker): Eventually, we won't have file contents in BuildJobState.
            // Then change this code to load file contents inline (only for missing files)
            sha1ToFileContents.put(file.hashCode, file.contents);
          }
        }

        List<String> contentHashes = ImmutableList.copyOf(sha1ToFileContents.keySet());
        CASContainsRequest containsReq = new CASContainsRequest();
        containsReq.setContentSha1s(contentHashes);
        FrontendRequest request = new FrontendRequest();
        request.setType(FrontendRequestType.CAS_CONTAINS);
        request.setCasContainsRequest(containsReq);
        FrontendResponse response = makeRequestChecked(request);

        Preconditions.checkState(
            response.getCasContainsResponse().exists.size() == contentHashes.size());
        List<Boolean> isPresent = response.getCasContainsResponse().exists;
        List<FileInfo> filesToBeUploaded = new LinkedList<>();
        for (int i = 0; i < isPresent.size(); ++i) {
          if (!isPresent.get(i)) {
            FileInfo file = new FileInfo();
            file.setContentHash(contentHashes.get(i));
            file.setContent(sha1ToFileContents.get(contentHashes.get(i)));
            filesToBeUploaded.add(file);
          }
        }

        request = new FrontendRequest();
        StoreLocalChangesRequest storeReq = new StoreLocalChangesRequest();
        storeReq.setFiles(filesToBeUploaded);
        request.setType(FrontendRequestType.STORE_LOCAL_CHANGES);
        request.setStoreLocalChangesRequest(storeReq);
        makeRequestChecked(request);
        // No response expected.
        return null;
      }
    });
  }

  public BuildJob createBuild() throws IOException {
    // Tell server to create the build and get the build id.
    CreateBuildRequest createTimeRequest = new CreateBuildRequest();
    createTimeRequest.setCreateTimestampMillis(System.currentTimeMillis());
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createTimeRequest);
    FrontendResponse response = makeRequestChecked(request);

    return response.getCreateBuildResponse().getBuildJob();
  }

  public BuildJob startBuild(BuildId id) throws IOException {
    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setBuildId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuildRequest(startRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getStartBuildResponse().getBuildJob();
    Preconditions.checkState(job.getBuildId().equals(id));
    return job;
  }

  public BuildJob pollBuild(BuildId id) throws IOException {
    // Create the poll for buildStatus request.
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setBuildId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatusRequest(statusRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getBuildStatusResponse().getBuildJob();
    Preconditions.checkState(job.getBuildId().equals(id));
    return job;
  }

}
