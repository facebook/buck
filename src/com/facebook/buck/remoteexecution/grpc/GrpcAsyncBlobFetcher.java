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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.event.CasBlobDownloadEvent;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.util.Scope;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/** GRPC implementation of the AsyncBlobFetcher. */
public class GrpcAsyncBlobFetcher implements AsyncBlobFetcher {

  private final String instanceName;
  private final ByteStreamStub byteStreamStub;
  private final BuckEventBus buckEventBus;

  public GrpcAsyncBlobFetcher(
      String instanceName,
      ByteStreamStub byteStreamStub,
      BuckEventBus buckEventBus,
      RemoteExecutionMetadata metadata) {
    this.instanceName = instanceName;
    this.byteStreamStub = GrpcHeaderHandler.wrapStubToSendMetadata(byteStreamStub, metadata);
    this.buckEventBus = buckEventBus;
  }

  @Override
  public ListenableFuture<ByteBuffer> fetch(Protocol.Digest digest) {
    /** Payload received on a fetch request. */
    class Data {
      ByteString data = ByteString.EMPTY;

      public ByteBuffer get() {
        return data.asReadOnlyByteBuffer();
      }

      public void concat(ByteString bytes) {
        data = data.concat(bytes);
      }
    }

    Data data = new Data();
    return closeScopeWhenFutureCompletes(
        CasBlobDownloadEvent.sendEvent(buckEventBus, 1, digest.getSize()),
        Futures.transform(
            GrpcRemoteExecutionClients.readByteStream(
                instanceName, digest, byteStreamStub, data::concat),
            ignored -> data.get(),
            MoreExecutors.directExecutor()));
  }

  @Override
  public ListenableFuture<Void> fetchToStream(Protocol.Digest digest, WritableByteChannel channel) {
    return closeScopeWhenFutureCompletes(
        CasBlobDownloadEvent.sendEvent(buckEventBus, 1, digest.getSize()),
        GrpcRemoteExecutionClients.readByteStream(
            instanceName,
            digest,
            byteStreamStub,
            byteString -> {
              for (ByteBuffer d : byteString.asReadOnlyByteBufferList()) {
                channel.write(d);
              }
            }));
  }

  private <T> ListenableFuture<T> closeScopeWhenFutureCompletes(
      Scope scope, ListenableFuture<T> future) {
    future.addListener(() -> scope.close(), MoreExecutors.directExecutor());
    return future;
  }
}
