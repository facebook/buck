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

package com.facebook.buck.logd.client;

import static org.easymock.EasyMock.mock;
import static org.junit.Assert.*;

import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogMessage;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.google.common.base.Charsets;
import com.google.rpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LogdStreamFactoryTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();
  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final TestHelper testHelper = mock(TestHelper.class);
  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();

  private LogDaemonClient client;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start());

    client =
        new LogdClient(
            InProcessChannelBuilder.forName(serverName).directExecutor(),
            new TestStreamObserverFactory(testHelper));
  }

  @Test
  public void createLogStream() {
    // Setting up mock grpc service
    List<LogMessage> messagesDelivered = new ArrayList<>();
    Status fakeResponse = Status.newBuilder().setCode(io.grpc.Status.Code.OK.value()).build();
    LogdServiceGrpc.LogdServiceImplBase openLogImpl =
        new LogdServiceGrpc.LogdServiceImplBase() {
          @Override
          public void createLogFile(
              CreateLogRequest request, StreamObserver<CreateLogResponse> responseObserver) {
            // first log file requested should have generated id = 1
            responseObserver.onNext(CreateLogResponse.newBuilder().setLogId(1).build());
            responseObserver.onCompleted();
          }

          @Override
          public StreamObserver<LogMessage> openLog(StreamObserver<Status> responseObserver) {
            return new StreamObserver<LogMessage>() {
              @Override
              public void onNext(LogMessage logMessage) {
                messagesDelivered.add(logMessage);
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onNext(fakeResponse);
                responseObserver.onCompleted();
              }
            };
          }
        };
    serviceRegistry.addService(openLogImpl);

    LogStreamFactory logStreamFactory = new LogdStreamFactory(client);
    String message = "hello world";
    try (OutputStream logdStream =
        logStreamFactory.createLogStream(getTestFilePath(), LogType.BUCK_LOG)) {
      logdStream.write(message.getBytes(Charsets.UTF_8));
    } catch (IOException e) {
      throw new AssertionError("Failed to stream message via logd", e);
    }

    assertEquals(1, messagesDelivered.size());
    assertEquals(message, messagesDelivered.get(0).getLogMessage());
  }

  private String getTestFilePath() {
    return tempFolder.getRoot().getAbsolutePath() + "/logs/buck.log";
  }
}
