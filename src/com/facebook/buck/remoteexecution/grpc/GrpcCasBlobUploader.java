/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse.Response;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.CasBlobUploadEvent;
import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/** GRPC implementation of the CasBlobUploader. */
public class GrpcCasBlobUploader implements CasBlobUploader {

  private final ContentAddressableStorageFutureStub storageStub;
  private final BuckEventBus buckEventBus;

  public GrpcCasBlobUploader(
      ContentAddressableStorageFutureStub storageStub, BuckEventBus buckEventBus) {
    this.storageStub = storageStub;
    this.buckEventBus = buckEventBus;
  }

  @Override
  public ImmutableSet<String> getMissingHashes(List<Digest> requiredDigests) throws IOException {
    try {
      FindMissingBlobsRequest.Builder requestBuilder = FindMissingBlobsRequest.newBuilder();
      requiredDigests.forEach(digest -> requestBuilder.addBlobDigests((GrpcProtocol.get(digest))));
      return storageStub
          .findMissingBlobs(requestBuilder.build())
          .get()
          .getMissingBlobDigestsList()
          .stream()
          .map(build.bazel.remote.execution.v2.Digest::getHash)
          .collect(ImmutableSet.toImmutableSet());
    } catch (InterruptedException | ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      e.printStackTrace();
      throw new BuckUncheckedExecutionException(e);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  @Override
  public ImmutableList<UploadResult> batchUpdateBlobs(ImmutableList<UploadData> blobs)
      throws IOException {
    long totalBlobSizeBytes = blobs.stream().mapToLong(blob -> blob.digest.getSize()).sum();
    try (Scope unused =
        CasBlobUploadEvent.sendEvent(buckEventBus, blobs.size(), totalBlobSizeBytes)) {
      BatchUpdateBlobsRequest.Builder requestBuilder = BatchUpdateBlobsRequest.newBuilder();
      for (UploadData blob : blobs) {
        try (InputStream dataStream = blob.data.get()) {
          requestBuilder.addRequests(
              BatchUpdateBlobsRequest.Request.newBuilder()
                  .setDigest(GrpcProtocol.get(blob.digest))
                  .setData(ByteString.readFrom(dataStream)));
        }
      }
      BatchUpdateBlobsResponse batchUpdateBlobsResponse =
          storageStub.batchUpdateBlobs(requestBuilder.build()).get();
      ImmutableList.Builder<UploadResult> resultBuilder = ImmutableList.builder();
      for (Response response : batchUpdateBlobsResponse.getResponsesList()) {
        resultBuilder.add(
            new UploadResult(
                new GrpcDigest(response.getDigest()),
                response.getStatus().getCode(),
                response.getStatus().getMessage()));
      }
      return resultBuilder.build();
    } catch (InterruptedException | ExecutionException e) {
      MoreThrowables.throwIfInitialCauseInstanceOf(e, IOException.class);
      throw new BuckUncheckedExecutionException(
          e,
          "When uploading a batch of blobs: <%s>.",
          blobs.stream().map(b -> b.data.describe()).collect(Collectors.joining(">, <")));
    }
  }
}
