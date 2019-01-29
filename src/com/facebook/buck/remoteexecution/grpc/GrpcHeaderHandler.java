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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.atomic.AtomicReference;

/** Provides operations over GRPC headers. */
public class GrpcHeaderHandler {
  private static final Logger LOG = Logger.get(GrpcHeaderHandler.class);

  private static final Key<byte[]> REMOTE_EXECUTION_METADATA_KEY =
      Metadata.Key.of("re-metadata-bin", Metadata.BINARY_BYTE_MARSHALLER);

  /**
   * Class that contains the GRPC stub and any RemoteExecutionMetadata returned in the GRPC
   * response.
   */
  public interface StubAndResponseMetadata<Stub extends AbstractStub<Stub>> {
    Stub getStub();

    RemoteExecutionMetadata getMetadata();
  }

  private GrpcHeaderHandler() {
    // Do not instantiate.
  }

  /** Convenience function to wrap the GRPC Stub for both receiving and sending metadata. */
  public static <Stub extends AbstractStub<Stub>>
      StubAndResponseMetadata<Stub> wrapStubToSendAndReceiveMetadata(
          Stub grpcStub, RemoteExecutionMetadata metadataToSend) {
    return wrapStubToReceiveMetadata(wrapStubToSendMetadata(grpcStub, metadataToSend));
  }

  /** Appends RemoteExecutionMetadata to the GRPC headers. */
  public static <Stub extends AbstractStub<Stub>> Stub wrapStubToSendMetadata(
      Stub grpcStub, RemoteExecutionMetadata metadata) {
    Metadata extraHeaders = new Metadata();
    extraHeaders.put(REMOTE_EXECUTION_METADATA_KEY, metadata.toByteArray());
    return MetadataUtils.attachHeaders(grpcStub, extraHeaders);
  }

  /** Receives RemoteExecutionMetadata from the initial GRPC headers. */
  public static <Stub extends AbstractStub<Stub>>
      StubAndResponseMetadata<Stub> wrapStubToReceiveMetadata(Stub grpcStub) {
    AtomicReference<Metadata> initialMetadata = new AtomicReference<>();
    AtomicReference<Metadata> trailingMetadata = new AtomicReference<>();
    Stub grpcWrappedStub =
        MetadataUtils.captureMetadata(grpcStub, initialMetadata, trailingMetadata);
    return new StubAndResponseMetadataImpl<>(grpcWrappedStub, initialMetadata, trailingMetadata);
  }

  private static class StubAndResponseMetadataImpl<Stub extends AbstractStub<Stub>>
      implements StubAndResponseMetadata<Stub> {

    private final Stub stub;
    private final AtomicReference<Metadata> initialMetadata;
    private final AtomicReference<Metadata> trailingMetadata;

    private StubAndResponseMetadataImpl(
        Stub wrappedStub,
        AtomicReference<Metadata> initialMetadata,
        AtomicReference<Metadata> trailingMetadata) {
      this.stub = wrappedStub;
      this.initialMetadata = initialMetadata;
      this.trailingMetadata = trailingMetadata;
    }

    @Override
    public Stub getStub() {
      return stub;
    }

    @Override
    public RemoteExecutionMetadata getMetadata() {

      RemoteExecutionMetadata.Builder metadata = RemoteExecutionMetadata.newBuilder();
      if (initialMetadata.get() != null) {
        metadata.mergeFrom(parseFromMetadata(initialMetadata.get()));
      }

      if (trailingMetadata.get() != null) {
        metadata.mergeFrom(parseFromMetadata(trailingMetadata.get()));
      }

      return metadata.build();
    }

    /** Parses RemoteExecutionMetadata from the GRPC Metadata. */
    private static RemoteExecutionMetadata parseFromMetadata(Metadata grpcMetadata) {
      if (grpcMetadata.containsKey(REMOTE_EXECUTION_METADATA_KEY)) {
        byte[] serialisedMetadata = grpcMetadata.get(REMOTE_EXECUTION_METADATA_KEY);
        if (serialisedMetadata != null) {
          try {
            return RemoteExecutionMetadata.parseFrom(serialisedMetadata);
          } catch (InvalidProtocolBufferException e) {
            LOG.error(
                "Failed to parse [%d bytes] for the RemoteExecutionMetadata.",
                serialisedMetadata.length);
          }
        }
      }

      return RemoteExecutionMetadata.getDefaultInstance();
    }
  }
}
