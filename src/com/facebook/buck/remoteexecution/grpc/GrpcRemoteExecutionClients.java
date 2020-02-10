/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient;
import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.facebook.buck.util.types.Unit;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** A RemoteExecution that sends jobs to a grpc-based remote execution service. */
public class GrpcRemoteExecutionClients implements RemoteExecutionClients {
  public static final Protocol PROTOCOL = new GrpcProtocol();
  private final ContentAddressedStorageClient storage;
  private final GrpcRemoteExecutionServiceClient executionService;
  private final ManagedChannel executionEngineChannel;
  private final ManagedChannel casChannel;
  private final MetadataProvider metadataProvider;

  /** A parsed read resource path. */
  @BuckStyleValue
  interface ParsedReadResource {
    String getInstanceName();

    Digest getDigest();
  }

  public GrpcRemoteExecutionClients(
      String instanceName,
      ManagedChannel executionEngineChannel,
      ManagedChannel casChannel,
      int casDeadline,
      MetadataProvider metadataProvider,
      BuckEventBus buckEventBus,
      RemoteExecutionStrategyConfig strategyConfig) {
    this.executionEngineChannel = executionEngineChannel;
    this.casChannel = casChannel;
    this.metadataProvider = metadataProvider;

    ByteStreamStub byteStreamStub = ByteStreamGrpc.newStub(casChannel);
    this.storage =
        createStorage(
            ContentAddressableStorageGrpc.newFutureStub(casChannel),
            byteStreamStub,
            casDeadline,
            instanceName,
            PROTOCOL,
            buckEventBus,
            strategyConfig);
    ExecutionStub executionStub = ExecutionGrpc.newStub(executionEngineChannel);
    this.executionService =
        new GrpcRemoteExecutionServiceClient(
            executionStub, byteStreamStub, instanceName, getProtocol(), casDeadline);
  }

  public static String getResourceName(String instanceName, Protocol.Digest digest) {
    return String.format("%s/blobs/%s/%d", instanceName, digest.getHash(), digest.getSize());
  }

  /** Reads a ByteStream onto the arg consumer. */
  public static ListenableFuture<Unit> readByteStream(
      String instanceName,
      Protocol.Digest digest,
      ByteStreamStub byteStreamStub,
      ThrowingConsumer<ByteString, IOException> dataConsumer,
      int casDeadline) {
    String name = getResourceName(instanceName, digest);
    SettableFuture<Unit> future = SettableFuture.create();
    byteStreamStub
        .withDeadlineAfter(casDeadline, TimeUnit.SECONDS)
        .read(
            ReadRequest.newBuilder().setResourceName(name).setReadLimit(0).setReadOffset(0).build(),
            new StreamObserver<ReadResponse>() {
              int size = 0;
              MessageDigest messageDigest = PROTOCOL.getMessageDigest();

              @Override
              public void onNext(ReadResponse value) {
                try {
                  ByteString data = value.getData();
                  size += data.size();
                  messageDigest.update(data.asReadOnlyByteBuffer());
                  dataConsumer.accept(data);
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
                String digestHash = HashCode.fromBytes(messageDigest.digest()).toString();
                if (size == digest.getSize() && digestHash.equals(digest.getHash())) {
                  future.set(null);
                } else {
                  future.setException(
                      new BuckUncheckedExecutionException(
                          "Digest of received bytes: "
                              + digestHash
                              + ":"
                              + size
                              + " doesn't match expected digest: "
                              + digest));
                }
              }
            });
    return future;
  }

  @Override
  public RemoteExecutionServiceClient getRemoteExecutionService() {
    return executionService;
  }

  @Override
  public ContentAddressedStorageClient getContentAddressedStorage() {
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

  private ContentAddressedStorageClient createStorage(
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      int casDeadline,
      String instanceName,
      Protocol protocol,
      BuckEventBus buckEventBus,
      RemoteExecutionStrategyConfig strategyConfig) {
    return new GrpcContentAddressableStorageClient(
        storageStub,
        byteStreamStub,
        casDeadline,
        instanceName,
        protocol,
        buckEventBus,
        metadataProvider.get(),
        strategyConfig.getOutputMaterializationThreads());
  }
}
