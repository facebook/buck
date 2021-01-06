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

import build.bazel.remote.execution.v2.BatchReadBlobsRequest;
import build.bazel.remote.execution.v2.BatchReadBlobsResponse;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.event.CasBlobDownloadEvent;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Unit;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** GRPC implementation of the AsyncBlobFetcher. */
public class GrpcAsyncBlobFetcher implements AsyncBlobFetcher {

  private final String instanceName;
  private final ByteStreamStub byteStreamStub;
  private final ContentAddressableStorageFutureStub storageStub;
  private final BuckEventBus buckEventBus;
  private final Protocol protocol;
  private final int casDeadline;

  public GrpcAsyncBlobFetcher(
      String instanceName,
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      BuckEventBus buckEventBus,
      RemoteExecutionMetadata metadata,
      Protocol protocol,
      int casDeadline) {
    this.instanceName = instanceName;
    this.storageStub = GrpcHeaderHandler.wrapStubToSendMetadata(storageStub, metadata);
    this.byteStreamStub = GrpcHeaderHandler.wrapStubToSendMetadata(byteStreamStub, metadata);
    this.buckEventBus = buckEventBus;
    this.protocol = protocol;
    this.casDeadline = casDeadline;
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
                instanceName, digest, byteStreamStub, data::concat, casDeadline),
            ignored -> data.get(),
            MoreExecutors.directExecutor()));
  }

  @Override
  public ListenableFuture<Unit> fetchToStream(Protocol.Digest digest, WritableByteChannel channel) {
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
            },
            casDeadline));
  }

  @Override
  public ListenableFuture<Unit> batchFetchBlobs(
      ImmutableMultimap<Protocol.Digest, Callable<WritableByteChannel>> requests,
      ImmutableMultimap<Protocol.Digest, SettableFuture<Unit>> futures) {
    Scope scope =
        CasBlobDownloadEvent.sendEvent(
            buckEventBus,
            requests.keySet().size(),
            requests.keySet().stream().mapToLong(Protocol.Digest::getSize).sum());
    BatchReadBlobsRequest.Builder requestBuilder = BatchReadBlobsRequest.newBuilder();
    requestBuilder.setInstanceName(instanceName);
    for (Protocol.Digest digest : requests.keySet()) {
      requestBuilder.addDigests(
          Digest.newBuilder().setHash(digest.getHash()).setSizeBytes(digest.getSize()).build());
    }

    ListenableFuture<BatchReadBlobsResponse> response =
        storageStub
            .withDeadlineAfter(casDeadline, TimeUnit.SECONDS)
            .batchReadBlobs(requestBuilder.build());
    return closeScopeWhenFutureCompletes(
        scope,
        Futures.transform(
            response,
            blobs -> {
              try {
                Preconditions.checkNotNull(blobs, "Invalid response from CAS server.");
                List<BatchReadBlobsResponse.Response> responses = blobs.getResponsesList();

                Preconditions.checkState(
                    responses.size() == requests.keySet().size(),
                    "Invalid response size from CAS server. Expected: %s Got: %s",
                    requests.keySet().size(),
                    responses.size());

                for (BatchReadBlobsResponse.Response batchResponse : responses) {
                  Protocol.Digest digest = new GrpcProtocol.GrpcDigest(batchResponse.getDigest());
                  Preconditions.checkState(
                      requests.containsKey(digest),
                      "Unknown digest: [%s] wasn't in requests.",
                      digest);
                  try {
                    handleResult(digest, batchResponse, requests.get(digest));
                    futures.get(digest).forEach(future -> future.set(null));
                  } catch (Exception e) {
                    futures.get(digest).forEach(future -> future.setException(e));
                  }
                }
              } catch (Exception e) {
                throw new BuckUncheckedExecutionException(
                    e, "When materializing digests: %s.", requests.keySet());
              }
              return null;
            },
            MoreExecutors.directExecutor()));
  }

  private void handleResult(
      Protocol.Digest digest,
      BatchReadBlobsResponse.Response batchResponse,
      ImmutableCollection<Callable<WritableByteChannel>> writableByteChannels)
      throws IOException {
    if (!Status.fromCodeValue(batchResponse.getStatus().getCode()).isOk()) {
      throw new BuckUncheckedExecutionException(
          String.format(
              "Invalid batchResponse from CAS server for digest: [%s], code: [%s] message: [%s].",
              digest, batchResponse.getStatus().getCode(), batchResponse.getStatus().getMessage()));
    }
    MessageDigest messageDigest = protocol.getMessageDigest();
    for (ByteBuffer dataByteBuffer : batchResponse.getData().asReadOnlyByteBufferList()) {
      messageDigest.update(dataByteBuffer.duplicate());
      for (Callable<WritableByteChannel> callable : writableByteChannels) {
        // Reset buffer position for each channel that's written to
        try (WritableByteChannel channel = callable.call()) {
          channel.write(dataByteBuffer.duplicate());
        } catch (Exception e) {
          throw new BuckUncheckedExecutionException("Unable to write " + digest + " to channel");
        }
      }
    }
    String receivedHash = HashCode.fromBytes(messageDigest.digest()).toString();
    if (digest.getSize() != batchResponse.getData().size()
        || !digest.getHash().equals(receivedHash)) {
      throw new BuckUncheckedExecutionException(
          "Digest of received bytes: "
              + receivedHash
              + ":"
              + batchResponse.getData().size()
              + " doesn't match expected digest: "
              + digest);
    }
  }

  private <T> ListenableFuture<T> closeScopeWhenFutureCompletes(
      Scope scope, ListenableFuture<T> future) {
    future.addListener(scope::close, MoreExecutors.directExecutor());
    return future;
  }
}
