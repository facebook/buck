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

import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;

/** Provides operations over GRPC headers. */
public class GrpcHeaderHandler {
  private static final Key<byte[]> REMOTE_EXECUTION_METADATA_KEY =
      Metadata.Key.of("re-metadata-bin", Metadata.BINARY_BYTE_MARSHALLER);

  private GrpcHeaderHandler() {
    // Do not instantiate.
  }

  /** Appends RemoteExecutionMetadata to the GRPC headers. */
  public static <Stub extends AbstractStub<Stub>> Stub getStubWithMetadata(
      Stub grpcStub, RemoteExecutionMetadata metadata) {
    Metadata extraHeaders = new Metadata();
    extraHeaders.put(REMOTE_EXECUTION_METADATA_KEY, metadata.toByteArray());
    return MetadataUtils.attachHeaders(grpcStub, extraHeaders);
  }
}
