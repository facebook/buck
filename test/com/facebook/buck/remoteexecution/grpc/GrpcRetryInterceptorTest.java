/*
 * Copyright 2019-present Facebook, Inc.
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

import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionBlockingStub;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionImplBase;
import com.facebook.buck.remoteexecution.grpc.retry.RetryClientInterceptor;
import com.facebook.buck.remoteexecution.grpc.retry.RetryPolicy;
import com.google.longrunning.Operation;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class GrpcRetryInterceptorTest {
  private static class ExecutionImpl extends ExecutionImplBase {
    public int calls = 0;
    private final Status status;

    public ExecutionImpl(Status status) {
      this.status = status;
    }

    @Override
    public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
      calls++;
      responseObserver.onError(status.asRuntimeException());
    }
  }

  private static class CallCounter implements Runnable {
    public int calls = 0;

    @Override
    public void run() {
      calls++;
    }
  }

  @Test
  public void testRetryOnUnavailable() throws IOException {
    String uniqueName = InProcessServerBuilder.generateName();
    ExecutionImpl service = new ExecutionImpl(Status.UNAVAILABLE);
    InProcessServerBuilder.forName(uniqueName).addService(service).build().start();
    CallCounter beforeRetry = new CallCounter();
    ManagedChannel channel =
        InProcessChannelBuilder.forName(uniqueName)
            .intercept(
                new RetryClientInterceptor(
                    RetryPolicy.builder().setMaxRetries(2).setBeforeRetry(beforeRetry).build()))
            .build();
    ExecutionBlockingStub stub = ExecutionGrpc.newBlockingStub(channel);
    try {
      stub.execute(ExecuteRequest.newBuilder().build()).forEachRemaining(resp -> {});
      Assert.fail("Final retry should cause an exception");
    } catch (StatusRuntimeException ex) {
      Assert.assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    Assert.assertEquals(3, service.calls);
    Assert.assertEquals(2, beforeRetry.calls);
  }

  @Test
  public void testNoRetryOnOk() throws IOException {
    String uniqueName = InProcessServerBuilder.generateName();
    ExecutionImpl service = new ExecutionImpl(Status.OK);
    InProcessServerBuilder.forName(uniqueName).addService(service).build().start();
    CallCounter beforeRetry = new CallCounter();
    ManagedChannel channel =
        InProcessChannelBuilder.forName(uniqueName)
            .intercept(
                new RetryClientInterceptor(
                    RetryPolicy.builder().setMaxRetries(2).setBeforeRetry(beforeRetry).build()))
            .build();
    ExecutionBlockingStub stub = ExecutionGrpc.newBlockingStub(channel);
    stub.execute(ExecuteRequest.newBuilder().build()).forEachRemaining(resp -> {});

    Assert.assertEquals(1, service.calls);
    Assert.assertEquals(0, beforeRetry.calls);
  }
}
