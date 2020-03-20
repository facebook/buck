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

package com.facebook.buck.logd;

import static org.junit.Assert.*;

import com.facebook.buck.logd.client.DefaultStreamObserverFactory;
import com.facebook.buck.logd.client.LogDaemonClient;
import com.facebook.buck.logd.client.LogdClient;
import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.facebook.buck.logd.proto.ShutdownRequest;
import com.facebook.buck.util.FakeProcess;
import com.google.rpc.Status;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LogdProviderTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();
  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private String serverName;

  @Before
  public void setUp() throws Exception {
    serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start());
  }

  @Test
  public void runLogdProviderWithLogdEnabled() {
    // This tests LogD mock server is up and running and that LogdProvider has a client that is
    // connected to this mock server and has correct connectivity states

    // Setting up mock grpc service
    LogdServiceGrpc.LogdServiceImplBase createLogFileImpl =
        new LogdServiceGrpc.LogdServiceImplBase() {
          @Override
          public void createLogFile(
              CreateLogRequest request, StreamObserver<CreateLogResponse> responseObserver) {
            // first log file requested should have generated id = 1
            responseObserver.onNext(CreateLogResponse.newBuilder().setLogId(1).build());
            responseObserver.onCompleted();
          }

          @Override
          public void shutdownServer(
              ShutdownRequest request, StreamObserver<Status> responseObserver) {
            responseObserver.onNext(Status.newBuilder().build());
            responseObserver.onCompleted();
          }
        };
    serviceRegistry.addService(createLogFileImpl);

    ManagedChannel channel;
    try (TestLogdProvider logdProvider = new TestLogdProvider(true)) {
      assertTrue(logdProvider.getLogdClient().isPresent());
      LogDaemonClient logdClient = logdProvider.getLogdClient().get();
      channel = logdClient.getChannel();

      assertEquals(ConnectivityState.IDLE, channel.getState(true));
      assertEquals(1, logdClient.createLogFile(getTestFilePath(), LogType.BUCK_LOG));
      assertEquals(ConnectivityState.READY, channel.getState(false));
    } catch (IOException e) {
      throw new AssertionError("Process fails to run", e);
    }

    assertEquals(ConnectivityState.SHUTDOWN, channel.getState(false));
  }

  @Test
  public void runLogdProviderWithLogdDisabled() {
    try (TestLogdProvider logdProvider = new TestLogdProvider(false)) {
      assertFalse(logdProvider.getLogdClient().isPresent());
    } catch (IOException e) {
      fail("There should not be any exception raised");
    }
  }

  private String getTestFilePath() {
    return tempFolder.getRoot().getAbsolutePath() + "/logs/buck.log";
  }

  private class TestLogdProvider extends LogdProvider {
    /**
     * Constructor for LogdProvider.
     *
     * @param isLogdEnabled determines whether LogD is used. If set to false, LogdProvider does
     *     nothing.
     * @throws IOException if process fails to run
     */
    public TestLogdProvider(boolean isLogdEnabled) throws IOException {
      super(isLogdEnabled);
    }

    @Override
    Process startProcess() {
      // must print a number to stdout or stderr or else LogdProvider.start() will be hung
      // although this port will not be used in our mock LogdClient
      return new FakeProcess(0, "8980", "");
    }

    @Override
    LogDaemonClient createLogdClient(int port) {
      return new LogdClient(
          InProcessChannelBuilder.forName(serverName).directExecutor(),
          new DefaultStreamObserverFactory());
    }
  }
}
