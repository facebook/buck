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
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import com.facebook.buck.remoteexecution.CasBlobUploader.UploadResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.google.common.collect.ImmutableList;
import com.google.rpc.Status.Builder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A simple CAS server backed by a {@link LocalContentAddressedStorage}. */
class LocalBackedCasServer extends ContentAddressableStorageImplBase {
  private final LocalContentAddressedStorage storage;

  LocalBackedCasServer(LocalContentAddressedStorage storage) {
    this.storage = storage;
  }

  @Override
  public void findMissingBlobs(
      FindMissingBlobsRequest request, StreamObserver<FindMissingBlobsResponse> responseObserver) {
    try {
      Stream<Digest> missing =
          storage.findMissing(
              request.getBlobDigestsList().stream()
                  .map(GrpcDigest::new)
                  .collect(Collectors.toList()));
      responseObserver.onNext(
          FindMissingBlobsResponse.newBuilder()
              .addAllMissingBlobDigests(missing.map(GrpcProtocol::get)::iterator)
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      e.printStackTrace();
      responseObserver.onError(e);
    }
  }

  @Override
  public void batchUpdateBlobs(
      BatchUpdateBlobsRequest request, StreamObserver<BatchUpdateBlobsResponse> responseObserver) {
    try {
      ImmutableList<UploadResult> uploadResults =
          storage.batchUpdateBlobs(
              request.getRequestsList().stream()
                  .map(
                      blobRequest ->
                          UploadDataSupplier.of(
                              new GrpcDigest(blobRequest.getDigest()),
                              () -> new ByteArrayInputStream(blobRequest.getData().toByteArray())))
                  .collect(ImmutableList.toImmutableList()));

      BatchUpdateBlobsResponse.Builder responseBuilder = BatchUpdateBlobsResponse.newBuilder();
      for (UploadResult uploadResult : uploadResults) {
        Builder statusBuilder = com.google.rpc.Status.newBuilder();
        statusBuilder.setCode(uploadResult.status);
        if (uploadResult.status != 0) {
          statusBuilder.setMessage(uploadResult.message);
        }
        responseBuilder.addResponses(
            Response.newBuilder()
                .setDigest(GrpcProtocol.get(uploadResult.digest))
                .setStatus(statusBuilder.build())
                .build());
      }
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      // idk how this should be done
      e.printStackTrace();
      responseObserver.onError(new StatusRuntimeException(Status.fromThrowable(e)));
    }
  }

  @Override
  public void getTree(GetTreeRequest request, StreamObserver<GetTreeResponse> responseObserver) {
    try {
      List<Directory> tree = storage.getTree(new GrpcDigest(request.getRootDigest()));
      responseObserver.onNext(
          GetTreeResponse.newBuilder()
              .addAllDirectories(tree.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
              .build());
    } catch (Exception e) {
      e.printStackTrace();
      responseObserver.onError(e);
    }
  }
}
