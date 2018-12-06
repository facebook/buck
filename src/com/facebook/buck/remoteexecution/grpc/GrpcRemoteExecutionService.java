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
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.log.TraceInfoProvider;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputDirectory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputFile;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Implementation of the GRPC client for the Remote Execution service. */
public class GrpcRemoteExecutionService implements RemoteExecutionService {
  private static final Logger LOG = Logger.get(GrpcRemoteExecutionService.class);

  private static final Key<? super String> TRACE_ID_KEY =
      Metadata.Key.of("trace-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Key<? super String> EDGE_ID_KEY =
      Metadata.Key.of("edge-id", Metadata.ASCII_STRING_MARSHALLER);
  private final ExecutionStub executionStub;
  private final ByteStreamStub byteStreamStub;
  private final String instanceName;
  private final Optional<TraceInfoProvider> traceInfoProvider;

  public GrpcRemoteExecutionService(
      ExecutionStub executionStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Optional<TraceInfoProvider> traceInfoProvider) {
    this.executionStub = executionStub;
    this.byteStreamStub = byteStreamStub;
    this.instanceName = instanceName;
    this.traceInfoProvider = traceInfoProvider;
  }

  private ExecutionStub getStubWithTraceInfo(Protocol.Digest actionDigest) {
    if (!traceInfoProvider.isPresent()) {
      return executionStub;
    }

    Metadata extraHeaders = new Metadata();
    String traceId = traceInfoProvider.get().getTraceId();
    extraHeaders.put(TRACE_ID_KEY, traceId);
    String edgeId =
        traceInfoProvider
            .get()
            .getEdgeId(RemoteExecutionActionEvent.actionDigestToString(actionDigest));
    extraHeaders.put(EDGE_ID_KEY, edgeId);
    return MetadataUtils.attachHeaders(executionStub, extraHeaders);
  }

  @Override
  public ListenableFuture<ExecutionResult> execute(Protocol.Digest actionDigest)
      throws IOException, InterruptedException {
    SettableFuture<Operation> future = SettableFuture.create();

    getStubWithTraceInfo(actionDigest)
        .execute(
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

    return Futures.transform(
        future,
        operation -> {
          Objects.requireNonNull(operation);
          if (operation.hasError()) {
            throw new RuntimeException("Execution failed: " + operation.getError().getMessage());
          }

          if (!operation.hasResponse()) {
            throw new RuntimeException(
                "Invalid operation response: missing ExecutionResponse object");
          }

          try {
            return getExecutionResult(
                operation.getResponse().unpack(ExecuteResponse.class).getResult());
          } catch (InvalidProtocolBufferException e) {
            throw new BuckUncheckedExecutionException(e);
          }
        });
  }

  private ExecutionResult getExecutionResult(ActionResult actionResult) {
    if (actionResult.getExitCode() != 0) {
      LOG.debug(
          "Got failed action from worker %s", actionResult.getExecutionMetadata().getWorker());
    }
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
            ByteString data = ByteString.EMPTY;
            GrpcRemoteExecutionClients.readByteStream(
                    instanceName,
                    new GrpcDigest(actionResult.getStderrDigest()),
                    byteStreamStub,
                    data::concat)
                .get();
            return Optional.of(data.toStringUtf8());
          } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
          }
        } else {
          System.err.println("Got raw stderr: " + stderrRaw.toStringUtf8());
          return Optional.of(stderrRaw.toStringUtf8());
        }
      }
    };
  }
}
