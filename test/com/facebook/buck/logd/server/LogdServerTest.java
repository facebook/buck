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

package com.facebook.buck.logd.server;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.mock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogMessage;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.google.rpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.easymock.Capture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Unit tests for LogD server */
public class LogdServerTest {
  /**
   * This rule manages automatic graceful shutdown for the registered channel at the end of test.
   */
  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private LogdServer server;
  private ManagedChannel inProcessChannel;
  private static final int PORT = 8980;

  @Before
  public void setUp() throws Exception {
    // Generate a unique in-process server name.
    String serverName = InProcessServerBuilder.generateName();

    // Use directExecutor for both InProcessServerBuilder and InProcessChannelBuilder can reduce the
    // usage timeouts and latches in test.
    server = new LogdServer(PORT, InProcessServerBuilder.forName(serverName).directExecutor());
    server.start();
    // Create a client channel and register for automatic graceful shutdown.
    inProcessChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  @After
  public void tearDown() {
    server.stop();
  }

  @Test
  public void createLogFile() {
    // This tests if a file is created upon a call to createLogFile to logD server
    // After logD successfully creates a file, it should return a response including a logFileId = 1
    String firstFilePath = getTestFilePath("/logs/buck.log");
    String secondFilePath = getTestFilePath("/logs/buck1.log");

    CreateLogRequest firstRequest =
        CreateLogRequest.newBuilder()
            .setLogType(LogType.BUCK_LOG)
            .setLogFilePath(firstFilePath)
            .build();
    CreateLogRequest secondRequest =
        CreateLogRequest.newBuilder()
            .setLogType(LogType.BUCK_LOG)
            .setLogFilePath(secondFilePath)
            .build();

    LogdServiceGrpc.LogdServiceBlockingStub blockingStub =
        LogdServiceGrpc.newBlockingStub(inProcessChannel);

    CreateLogResponse firstResponse = blockingStub.createLogFile(firstRequest);
    CreateLogResponse secondResponse = blockingStub.createLogFile(secondRequest);

    // check files were created
    assertTrue(new File(firstFilePath).exists());
    assertTrue(new File(secondFilePath).exists());
    // check for correct id increments
    assertEquals(firstResponse.getLogId(), 1);
    assertEquals(secondResponse.getLogId(), 2);
  }

  @Test
  public void appendLog() {
    // This tests if appendLog() does append messages to a temp file

    // temp file to stream messages to
    String filePath = getTestFilePath("/logs/buck.log");
    LogType logType = LogType.BUCK_LOG;

    CreateLogRequest request =
        CreateLogRequest.newBuilder().setLogType(logType).setLogFilePath(filePath).build();

    LogdServiceGrpc.LogdServiceBlockingStub blockingStub =
        LogdServiceGrpc.newBlockingStub(inProcessChannel);

    CreateLogResponse response = blockingStub.createLogFile(request);

    // a log file should be created and logD returns 1 as the log file identifier
    assertTrue(new File(filePath).exists());
    int logId = response.getLogId();
    assertEquals(1, logId);

    LogMessage logMessage1 =
        LogMessage.newBuilder().setLogId(logId).setLogMessage("Hello, world!").build();
    LogMessage logMessage2 =
        LogMessage.newBuilder().setLogId(logId).setLogMessage("Hello, again!").build();

    @SuppressWarnings("unchecked")
    StreamObserver<Status> responseObserver = mock(StreamObserver.class);
    LogdServiceGrpc.LogdServiceStub stub = LogdServiceGrpc.newStub(inProcessChannel);
    Capture<Status> statusCaptor = Capture.newInstance();

    StreamObserver<LogMessage> requestObserver = stub.openLog(responseObserver);

    StringBuilder expected = new StringBuilder();

    requestObserver.onNext(logMessage1);
    expected.append(logMessage1.getLogMessage());
    expected.append(System.lineSeparator());

    requestObserver.onNext(logMessage2);
    expected.append(logMessage2.getLogMessage());
    expected.append(System.lineSeparator());

    // set expectations before client closes stream
    responseObserver.onNext(capture(statusCaptor));
    expectLastCall();
    responseObserver.onCompleted();
    expectLastCall();
    replay(responseObserver);

    // client closes stream observer
    requestObserver.onCompleted();

    // verify our expectations are true
    verify(responseObserver);

    assertEquals(statusCaptor.getValue().getCode(), io.grpc.Status.Code.OK.value());

    // assert log file contains log messages
    try {
      assertEquals(expected.toString(), readFile(filePath));
    } catch (IOException e) {
      fail("Failed to read " + filePath + " to check log contents. Please re-run this test...");
    }
  }

  private String readFile(String filePath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(filePath)));
  }

  private String getTestFilePath(String relativePath) {
    return tempFolder.getRoot().getAbsolutePath() + relativePath;
  }
}
