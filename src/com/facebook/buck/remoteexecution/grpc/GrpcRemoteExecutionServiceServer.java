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

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionImplBase;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Action;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Command;
import com.facebook.buck.remoteexecution.util.ActionRunner;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
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
import io.grpc.BindableService;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/** A really simple implementation of remote execution (and CAS). Used for testing/debugging. */
public class GrpcRemoteExecutionServiceServer {
  private final LocalContentAddressedStorage storage;
  private final Path workDir;

  // Services
  private final LocalBackedCasServer casImpl;
  private final LocalBackedByteStreamServer byteStreamImpl;
  private final OperationsFutureImpl operationsFutureImpl;
  private final ExecutionImpl executionImpl;

  public GrpcRemoteExecutionServiceServer(LocalContentAddressedStorage storage, Path workDir) {
    this.storage = storage;
    this.workDir = workDir;
    this.casImpl = new LocalBackedCasServer(storage);
    this.byteStreamImpl = new LocalBackedByteStreamServer(storage);
    this.operationsFutureImpl = new OperationsFutureImpl();
    this.executionImpl = new ExecutionImpl();
  }

  public ImmutableList<BindableService> getServices() {
    return ImmutableList.of(casImpl, byteStreamImpl, operationsFutureImpl, executionImpl);
  }

  private class ExecutionImpl extends ExecutionImplBase {
    @Override
    public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
      try {
        // Don't really need to be too careful here about constructing a unique directory.
        Action action = storage.materializeAction(new GrpcDigest(request.getActionDigest()));
        String name =
            String.format("%s-%d", action.getInputRootDigest().getHash(), new Random().nextLong());
        Path buildDir = workDir.resolve(name);
        Files.createDirectories(buildDir);
        try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
          Command command =
              storage
                  .materializeInputs(
                      buildDir, action.getInputRootDigest(), Optional.of(action.getCommandDigest()))
                  .get();

          ActionRunner.ActionResult actionResult =
              new ActionRunner(
                      new GrpcProtocol(),
                      new DefaultBuckEventBus(new DefaultClock(), new BuildId("RemoteExec")))
                  .runAction(
                      command.getCommand(),
                      command.getEnvironment(),
                      command.getOutputDirectories().stream()
                          .map(Paths::get)
                          .collect(ImmutableSet.toImmutableSet()),
                      buildDir);

          Futures.getUnchecked(storage.addMissing(actionResult.requiredData));

          ActionResult.Builder grpcActionResultBuilder = ActionResult.newBuilder();
          grpcActionResultBuilder
              .setExitCode(actionResult.exitCode)
              .setStdoutRaw(ByteString.copyFromUtf8(actionResult.stdout))
              .setStderrRaw(ByteString.copyFromUtf8(actionResult.stderr))
              .addAllOutputFiles(
                  actionResult.outputFiles.stream()
                      .map(GrpcProtocol::get)
                      .collect(Collectors.toList()))
              .addAllOutputDirectories(
                  actionResult.outputDirectories.stream()
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
