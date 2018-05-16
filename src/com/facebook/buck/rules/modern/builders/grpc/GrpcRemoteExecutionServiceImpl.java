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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.modern.builders.ActionRunner;
import com.facebook.buck.rules.modern.builders.LocalContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadData;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadResult;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.Protocol.Command;
import com.facebook.buck.rules.modern.builders.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.BatchUpdateBlobsRequest;
import com.google.devtools.remoteexecution.v1test.BatchUpdateBlobsResponse;
import com.google.devtools.remoteexecution.v1test.BatchUpdateBlobsResponse.Response;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.ExecuteRequest;
import com.google.devtools.remoteexecution.v1test.ExecuteResponse;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc.ExecutionImplBase;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsRequest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsResponse;
import com.google.devtools.remoteexecution.v1test.GetTreeRequest;
import com.google.devtools.remoteexecution.v1test.GetTreeResponse;
import com.google.longrunning.CancelOperationRequest;
import com.google.longrunning.DeleteOperationRequest;
import com.google.longrunning.GetOperationRequest;
import com.google.longrunning.ListOperationsRequest;
import com.google.longrunning.ListOperationsResponse;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsGrpc.OperationsImplBase;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.rpc.Status.Builder;
import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A really simple implementation of remote execution (and CAS). Used for testing/debugging. */
public class GrpcRemoteExecutionServiceImpl {
  private final LocalContentAddressedStorage storage;
  private final Path workDir;

  // Services
  private final LocalBackedCasImpl casImpl;
  private final LocalBackedByteStreamImpl byteStreamImpl;
  private final OperationsFutureImpl operationsFutureImpl;
  private final ExecutionImpl executionImpl;

  public GrpcRemoteExecutionServiceImpl(LocalContentAddressedStorage storage, Path workDir) {
    this.storage = storage;
    this.workDir = workDir;
    this.casImpl = new LocalBackedCasImpl();
    this.byteStreamImpl = new LocalBackedByteStreamImpl();
    this.operationsFutureImpl = new OperationsFutureImpl();
    this.executionImpl = new ExecutionImpl();
  }

  public ImmutableList<BindableService> getServices() {
    return ImmutableList.of(casImpl, byteStreamImpl, operationsFutureImpl, executionImpl);
  }

  private class LocalBackedCasImpl extends ContentAddressableStorageImplBase {
    private LocalBackedCasImpl() {}

    @Override
    public void findMissingBlobs(
        FindMissingBlobsRequest request,
        StreamObserver<FindMissingBlobsResponse> responseObserver) {
      try {
        Stream<Protocol.Digest> missing =
            storage.findMissing(
                request
                    .getBlobDigestsList()
                    .stream()
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
        BatchUpdateBlobsRequest request,
        StreamObserver<BatchUpdateBlobsResponse> responseObserver) {
      try {
        ImmutableList<UploadResult> uploadResults =
            storage.batchUpdateBlobs(
                request
                    .getRequestsList()
                    .stream()
                    .map(
                        blobRequest ->
                            new UploadData(
                                new GrpcDigest(blobRequest.getContentDigest()),
                                () ->
                                    new ByteArrayInputStream(blobRequest.getData().toByteArray())))
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
                  .setBlobDigest(GrpcProtocol.get(uploadResult.digest))
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
        List<Protocol.Directory> tree = storage.getTree(new GrpcDigest(request.getRootDigest()));
        responseObserver.onNext(
            GetTreeResponse.newBuilder()
                .addAllDirectories(
                    tree.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
                .build());
      } catch (Exception e) {
        e.printStackTrace();
        responseObserver.onError(e);
      }
    }
  }

  public static final Pattern RESOURCE_NAME_PATTERN =
      Pattern.compile("([^/]*)/blobs/([^/]*)/([0-9]*)");

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

  private class LocalBackedByteStreamImpl extends ByteStreamImplBase {
    @Override
    public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
      try {
        ParsedReadResource parsedResource = parseResourceName(request.getResourceName());
        try (InputStream data = storage.getData(new GrpcDigest(parsedResource.getDigest()))) {
          responseObserver.onNext(
              ReadResponse.newBuilder().setData(ByteString.readFrom(data)).build());
        }
        responseObserver.onCompleted();
      } catch (Exception e) {
        e.printStackTrace();
        responseObserver.onError(e);
      }
    }

    @Override
    public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> responseObserver) {
      // unimplemented.
      return super.write(responseObserver);
    }

    @Override
    public void queryWriteStatus(
        QueryWriteStatusRequest request,
        StreamObserver<QueryWriteStatusResponse> responseObserver) {
      // unimplemented.
      super.queryWriteStatus(request, responseObserver);
    }
  }

  private class ExecutionImpl extends ExecutionImplBase {
    @Override
    public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
      try {
        // Don't really need to be too careful here about constructing a unique directory.
        Action action = request.getAction();
        String name =
            String.format("%s-%d", action.getInputRootDigest().getHash(), new Random().nextLong());
        Path buildDir = workDir.resolve(name);
        Files.createDirectories(buildDir);
        try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
          Command command =
              storage
                  .materializeInputs(
                      buildDir,
                      new GrpcDigest(action.getInputRootDigest()),
                      Optional.of(new GrpcDigest(action.getCommandDigest())))
                  .get();

          ActionRunner.ActionResult actionResult =
              new ActionRunner(
                      new GrpcProtocol(),
                      new DefaultBuckEventBus(new DefaultClock(), new BuildId("RemoteExec")))
                  .runAction(
                      command.getCommand(),
                      command.getEnvironment(),
                      action
                          .getOutputDirectoriesList()
                          .asByteStringList()
                          .stream()
                          .map(ByteString::toStringUtf8)
                          .map(Paths::get)
                          .collect(ImmutableSet.toImmutableSet()),
                      buildDir);

          storage.addMissing(actionResult.requiredData);

          ActionResult.Builder grpcActionResultBuilder = ActionResult.newBuilder();
          grpcActionResultBuilder
              .setExitCode(actionResult.exitCode)
              .setStdoutRaw(ByteString.copyFromUtf8(actionResult.stdout))
              .setStderrRaw(ByteString.copyFromUtf8(actionResult.stderr))
              .addAllOutputFiles(
                  actionResult
                      .outputFiles
                      .stream()
                      .map(GrpcProtocol::get)
                      .collect(Collectors.toList()))
              .addAllOutputDirectories(
                  actionResult
                      .outputDirectories
                      .stream()
                      .map(GrpcProtocol::get)
                      .collect(Collectors.toList()));

          responseObserver.onNext(
              Operation.newBuilder()
                  .setDone(true)
                  .setResponse(
                      Any.pack(
                          ExecuteResponse.newBuilder()
                              .setResult(grpcActionResultBuilder)
                              .setStatus(
                                  com.google.rpc.Status.newBuilder().setCode(Code.OK.value()))
                              .setCachedResult(false)
                              .build()))
                  .build());
        }
        responseObserver.onCompleted();
      } catch (Exception e) {
        e.printStackTrace();
        responseObserver.onError(e);
      }
    }
  }

  private class OperationsFutureImpl extends OperationsImplBase {
    @Override
    public void listOperations(
        ListOperationsRequest request, StreamObserver<ListOperationsResponse> responseObserver) {
      // unimplemented
      super.listOperations(request, responseObserver);
    }

    @Override
    public void getOperation(
        GetOperationRequest request, StreamObserver<Operation> responseObserver) {
      // unimplemented
      super.getOperation(request, responseObserver);
    }

    @Override
    public void deleteOperation(
        DeleteOperationRequest request, StreamObserver<Empty> responseObserver) {
      // unimplemented
      super.deleteOperation(request, responseObserver);
    }

    @Override
    public void cancelOperation(
        CancelOperationRequest request, StreamObserver<Empty> responseObserver) {
      // unimplemented
      super.cancelOperation(request, responseObserver);
    }
  }
}
