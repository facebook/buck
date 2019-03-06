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

import build.bazel.remote.execution.v2.Digest;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A simple ByteStream server backed by a {@link LocalContentAddressedStorage}. */
class LocalBackedByteStreamServer extends ByteStreamImplBase {
  /**
   * Matches blob patterns as specified by the remote execution api:
   * {instance_name}/blobs/{hash}/{size}
   */
  public static final Pattern RESOURCE_NAME_PATTERN =
      Pattern.compile("([^/]*)/blobs/([^/]*)/([0-9]*)");

  private static final int BYTESTREAM_READ_CHUNK_SIZE = 1 * 1024 * 1024;

  private final LocalContentAddressedStorage storage;

  LocalBackedByteStreamServer(LocalContentAddressedStorage storage) {
    this.storage = storage;
  }

  public static ParsedReadResource parseResourceName(String resource) {
    Matcher matcher = RESOURCE_NAME_PATTERN.matcher(resource);
    Preconditions.checkState(matcher.matches());
    return ParsedReadResource.of(
        matcher.group(1),
        Digest.newBuilder()
            .setHash(matcher.group(2))
            .setSizeBytes(Long.parseLong(matcher.group(3)))
            .build());
  }

  @Override
  public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
    try {
      ParsedReadResource parsedResource = parseResourceName(request.getResourceName());
      byte[] buffer = new byte[BYTESTREAM_READ_CHUNK_SIZE];
      try (InputStream data = storage.getData(new GrpcDigest(parsedResource.getDigest()))) {
        while (true) {
          int read = data.read(buffer);
          if (read == -1) {
            break;
          }
          responseObserver.onNext(
              ReadResponse.newBuilder().setData(ByteString.copyFrom(buffer, 0, read)).build());
        }
      }
      responseObserver.onCompleted();
    } catch (Exception e) {
      e.printStackTrace();
      responseObserver.onError(e);
    }
  }

  @Override
  public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> responseObserver) {
    // Unimplemented, Buck client doesn't use this.
    return super.write(responseObserver);
  }

  @Override
  public void queryWriteStatus(
      QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> responseObserver) {
    // Unimplemented, Buck client doesn't use this.
    super.queryWriteStatus(request, responseObserver);
  }
}
