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

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

/** A RemoteExecution that sends jobs to a grpc-based remote execution service. */
public class GrpcRemoteExecutionClients implements RemoteExecutionClients {
  public static final Protocol PROTOCOL = new GrpcProtocol();
  private final ContentAddressedStorage storage;
  private final GrpcRemoteExecutionService executionService;
  private final ManagedChannel executionEngineChannel;
  private final ManagedChannel casChannel;

  /** A parsed read resource path. */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractParsedReadResource {
    String getInstanceName();

    Digest getDigest();
  }

  public GrpcRemoteExecutionClients(
      String instanceName,
      ManagedChannel executionEngineChannel,
      ManagedChannel casChannel,
      MetadataProvider metadataProvider,
      BuckEventBus buckEventBus) {
    this.executionEngineChannel = executionEngineChannel;
    this.casChannel = casChannel;

    ByteStreamStub byteStreamStub = ByteStreamGrpc.newStub(casChannel);
    this.storage =
        createStorage(
            ContentAddressableStorageGrpc.newFutureStub(casChannel),
            byteStreamStub,
            instanceName,
            PROTOCOL,
            buckEventBus);
    ExecutionStub executionStub = ExecutionGrpc.newStub(executionEngineChannel);
    this.executionService =
        new GrpcRemoteExecutionService(
            executionStub, byteStreamStub, instanceName, metadataProvider);
  }

  private static String getReadResourceName(String instanceName, Protocol.Digest digest) {
    return String.format("%s/blobs/%s/%d", instanceName, digest.getHash(), digest.getSize());
  }

  /** Reads a ByteStream onto the arg consumer. */
  public static ListenableFuture<Void> readByteStream(
      String instanceName,
      Protocol.Digest digest,
      ByteStreamStub byteStreamStub,
      ThrowingConsumer<ByteString, IOException> dataConsumer) {
    String name = getReadResourceName(instanceName, digest);
    SettableFuture<Void> future = SettableFuture.create();
    byteStreamStub.read(
        ReadRequest.newBuilder().setResourceName(name).setReadLimit(0).setReadOffset(0).build(),
        new StreamObserver<ReadResponse>() {
          @Override
          public void onNext(ReadResponse value) {
            try {
              dataConsumer.accept(value.getData());
            } catch (IOException e) {
              onError(e);
            }
          }

          @Override
          public void onError(Throwable t) {
            future.setException(t);
          }

          @Override
          public void onCompleted() {
            future.set(null);
          }
        });
    return future;
  }

  @Override
  public RemoteExecutionService getRemoteExecutionService() {
    return executionService;
  }

  @Override
  public ContentAddressedStorage getContentAddressedStorage() {
    return storage;
  }

  @Override
  public Protocol getProtocol() {
    return PROTOCOL;
  }

  @Override
  public void close() throws IOException {
    closeChannel(casChannel);
    closeChannel(executionEngineChannel);
  }

  private static void closeChannel(ManagedChannel channel) {
    channel.shutdown();
    try {
      channel.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private ContentAddressedStorage createStorage(
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Protocol protocol,
      BuckEventBus buckEventBus) {
    return new GrpcContentAddressableStorage(
        storageStub, byteStreamStub, instanceName, protocol, buckEventBus);
  }
}
