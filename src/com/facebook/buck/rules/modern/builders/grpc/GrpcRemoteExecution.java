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

package com.facebook.buck.rules.modern.builders.grpc;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse.Response;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.modern.builders.AsyncBlobFetcher;
import com.facebook.buck.rules.modern.builders.CasBlobUploader;
import com.facebook.buck.rules.modern.builders.ContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadData;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadResult;
import com.facebook.buck.rules.modern.builders.OutputsMaterializer;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.Protocol.OutputDirectory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputFile;
import com.facebook.buck.rules.modern.builders.RemoteExecution;
import com.facebook.buck.rules.modern.builders.RemoteExecutionService;
import com.facebook.buck.rules.modern.builders.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.rules.modern.builders.grpc.GrpcProtocol.GrpcOutputDirectory;
import com.facebook.buck.rules.modern.builders.grpc.GrpcProtocol.GrpcOutputFile;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** A RemoteExecution that sends jobs to a grpc-based remote execution service. */
public class GrpcRemoteExecution extends RemoteExecution {
  public static final Protocol PROTOCOL = new GrpcProtocol();
  private final ContentAddressedStorage storage;
  private final GrpcRemoteExecutionService executionService;

  private static String getReadResourceName(String instanceName, Protocol.Digest digest) {
    return String.format("%s/blobs/%s/%d", instanceName, digest.getHash(), digest.getSize());
  }

  /** A parsed read resource path. */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractParsedReadResource {
    String getInstanceName();

    Digest getDigest();
  }

  GrpcRemoteExecution(String instanceName, ManagedChannel channel, BuckEventBus eventBus)
      throws IOException {
    super(eventBus, PROTOCOL);
    ByteStreamStub byteStreamStub = ByteStreamGrpc.newStub(channel);
    this.storage =
        createStorage(
            ContentAddressableStorageGrpc.newFutureStub(channel),
            byteStreamStub,
            instanceName,
            PROTOCOL);
    this.executionService =
        new GrpcRemoteExecutionService(
            ExecutionGrpc.newStub(channel), byteStreamStub, instanceName);
  }

  @Override
  protected RemoteExecutionService getExecutionService() {
    return executionService;
  }

  @Override
  public ContentAddressedStorage getStorage() {
    return storage;
  }

  private ContentAddressedStorage createStorage(
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Protocol protocol) {
    MultiThreadedBlobUploader uploader =
        new MultiThreadedBlobUploader(
            1000,
            10 * 1024 * 1024,
            MostExecutors.newMultiThreadExecutor("blob-uploader", 4),
            new CasBlobUploader() {
              @Override
              public ImmutableSet<String> getMissingHashes(List<Protocol.Digest> requiredDigests)
                  throws IOException {
                try {
                  FindMissingBlobsRequest.Builder requestBuilder =
                      FindMissingBlobsRequest.newBuilder();
                  requiredDigests.forEach(
                      digest -> requestBuilder.addBlobDigests((GrpcProtocol.get(digest))));
                  return storageStub
                      .findMissingBlobs(requestBuilder.build())
                      .get()
                      .getMissingBlobDigestsList()
                      .stream()
                      .map(Digest::getHash)
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
                try {
                  BatchUpdateBlobsRequest.Builder requestBuilder =
                      BatchUpdateBlobsRequest.newBuilder();
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
                  e.printStackTrace();
                  throw new BuckUncheckedExecutionException(e);
                }
              }
            });

    OutputsMaterializer outputsMaterializer =
        new OutputsMaterializer(
            new AsyncBlobFetcher() {
              @Override
              public ListenableFuture<ByteBuffer> fetch(Protocol.Digest digest) {
                return Futures.transform(
                    readByteStream(instanceName, digest, byteStreamStub),
                    string -> string.asReadOnlyByteBuffer());
              }

              @Override
              public void fetchToStream(Protocol.Digest digest, OutputStream outputStream) {
                throw new UnsupportedOperationException();
              }
            },
            protocol);
    return new ContentAddressedStorage() {
      @Override
      public void addMissing(
          ImmutableMap<Protocol.Digest, ThrowingSupplier<InputStream, IOException>> data)
          throws IOException {
        uploader.addMissing(data);
      }

      @Override
      public void materializeOutputs(
          List<OutputDirectory> outputDirectories, List<OutputFile> outputFiles, Path root)
          throws IOException {
        outputsMaterializer.materialize(outputDirectories, outputFiles, root);
      }
    };
  }

  private static ListenableFuture<ByteString> readByteStream(
      String instanceName, Protocol.Digest digest, ByteStreamStub byteStreamStub) {
    String name = getReadResourceName(instanceName, digest);
    SettableFuture<ByteString> future = SettableFuture.create();
    byteStreamStub.read(
        ReadRequest.newBuilder().setResourceName(name).setReadLimit(0).setReadOffset(0).build(),
        new StreamObserver<ReadResponse>() {
          ByteString data = ByteString.EMPTY;

          @Override
          public void onNext(ReadResponse value) {
            data = data.concat(value.getData());
          }

          @Override
          public void onError(Throwable t) {
            future.setException(t);
          }

          @Override
          public void onCompleted() {
            future.set(data);
          }
        });
    return future;
  }

  private static class GrpcRemoteExecutionService implements RemoteExecutionService {
    private final ExecutionStub executionStub;
    private final ByteStreamStub byteStreamStub;
    private final String instanceName;

    private GrpcRemoteExecutionService(
        ExecutionStub executionStub, ByteStreamStub byteStreamStub, String instanceName) {
      this.executionStub = executionStub;
      this.byteStreamStub = byteStreamStub;
      this.instanceName = instanceName;
    }

    @Override
    public ExecutionResult execute(Protocol.Digest actionDigest)
        throws IOException, InterruptedException {
      SettableFuture<Operation> future = SettableFuture.create();

      executionStub.execute(
          ExecuteRequest.newBuilder()
              .setInstanceName(instanceName)
              .setActionDigest(GrpcProtocol.get(actionDigest))
              .setSkipCacheLookup(false)
              .build(),
          new StreamObserver<Operation>() {
            @Nullable Operation op = null;

            @Override
            public void onNext(Operation value) {
              op = value;
            }

            @Override
            public void onError(Throwable t) {
              future.setException(t);
            }

            @Override
            public void onCompleted() {
              future.set(op);
            }
          });

      try {
        ActionResult actionResult =
            future.get().getResponse().unpack(ExecuteResponse.class).getResult();
        return new ExecutionResult() {
          @Override
          public List<OutputDirectory> getOutputDirectories() {
            return actionResult
                .getOutputDirectoriesList()
                .stream()
                .map(GrpcOutputDirectory::new)
                .collect(Collectors.toList());
          }

          @Override
          public List<OutputFile> getOutputFiles() {
            return actionResult
                .getOutputFilesList()
                .stream()
                .map(GrpcOutputFile::new)
                .collect(Collectors.toList());
          }

          @Override
          public int getExitCode() {
            return actionResult.getExitCode();
          }

          @Override
          public Optional<String> getStderr() {
            ByteString stderrRaw = actionResult.getStderrRaw();
            if (stderrRaw == null
                || (stderrRaw.isEmpty() && actionResult.getStderrDigest().getSizeBytes() > 0)) {
              System.err.println("Got stderr digest.");
              try {
                return Optional.of(
                    readByteStream(
                            instanceName,
                            new GrpcDigest(actionResult.getStderrDigest()),
                            byteStreamStub)
                        .get()
                        .toStringUtf8());
              } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
              }
            } else {
              System.err.println("Got raw stderr: " + stderrRaw.toStringUtf8());
              return Optional.of(stderrRaw.toStringUtf8());
            }
          }
        };
      } catch (ExecutionException e) {
        Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
        Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
        e.printStackTrace();
        throw new BuckUncheckedExecutionException(e.getCause());
      }
    }
  }
}
