/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.worker;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@SuppressWarnings("resource") // Closing alters the test data.
public class WorkerProcessProtocolZeroTest {

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ByteArrayOutputStream dummyOutputStream;
  private ByteArrayInputStream dummyInputStream;

  @Before
  public void setUp() {
    dummyOutputStream = new ByteArrayOutputStream();
    dummyInputStream = inputStream("");
  }

  @Test
  public void testSendHandshake() throws IOException {
    int handshakeID = 123;
    String expectedJson =
        String.format(
            "[{\"id\":%d,\"type\":\"handshake\",\"protocol_version\":\"0\",\"capabilities\":[]}",
            handshakeID);

    InputStream dummyJsonReader = inputStream(expectedJson);

    ByteArrayOutputStream jsonSentToWorkerProcess = new ByteArrayOutputStream();
    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            jsonSentToWorkerProcess, dummyJsonReader, newTempFile(), () -> {}, () -> true);

    protocol.handshake(handshakeID);
    assertThat(jsonSentToWorkerProcess.toString(), Matchers.containsString(expectedJson));
  }

  @Test
  public void testSendCommand() throws IOException {
    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, dummyInputStream, newTempFile(), () -> {}, () -> true);

    int messageID = 123;
    Path argsPath = Paths.get("args");
    Path stdoutPath = Paths.get("stdout");
    Path stderrPath = Paths.get("stderr");
    protocol.send(messageID, WorkerProcessCommand.of(argsPath, stdoutPath, stderrPath));
    String expectedJson =
        String.format(
            "{\"id\":%d,\"type\":\"command\","
                + "\"args_path\":\"%s\",\"stdout_path\":\"%s\",\"stderr_path\":\"%s\"}",
            messageID, argsPath.toString(), stdoutPath.toString(), stderrPath.toString());
    assertThat(dummyOutputStream.toString(), Matchers.containsString(expectedJson));
  }

  private InputStream createMockJsonReaderForReceiveHandshake(
      int handshakeID, String type, String protocolVersion) {
    String jsonToBeRead =
        String.format(
            "[{\"id\":%d,\"type\":\"%s\",\"protocol_version\":\"%s\",\"capabilities\":[]}",
            handshakeID, type, protocolVersion);
    return inputStream(jsonToBeRead);
  }

  @Test
  public void testReceiveHandshake() throws IOException {
    int handshakeID = 123;
    InputStream jsonReader = createMockJsonReaderForReceiveHandshake(handshakeID, "handshake", "0");

    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, jsonReader, newTempFile(), () -> {}, () -> true);

    protocol.handshake(handshakeID);
  }

  private InputStream createMockJsonReaderForReceiveCommandResponse(
      int messageID, String type, int exitCode) {
    String jsonToBeRead =
        String.format("{\"id\":%d,\"type\":\"%s\",\"exit_code\":%d}", messageID, type, exitCode);
    return inputStream(jsonToBeRead);
  }

  @Test
  public void testReceiveCommandResponse() throws IOException {
    int messageID = 123;
    InputStream jsonReader = createMockJsonReaderForReceiveCommandResponse(messageID, "result", 0);

    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, jsonReader, newTempFile(), () -> {}, () -> true);

    protocol.receiveCommandResponse(messageID);
  }

  @Test
  public void testReceiveCommandResponseWithMalformedJSON() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Error receiving command response");

    String malformedJson = "><(((('> blub";

    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, inputStream(malformedJson), newTempFile(), () -> {}, () -> true);

    protocol.receiveCommandResponse(123);
  }

  @Test
  public void testReceiveCommandResponseWithIncorrectMessageID() throws IOException {
    int messageID = 123;
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format("Expected response's \"id\" value to be \"%d\"", messageID));

    int differentMessageID = 456;
    InputStream jsonReader =
        createMockJsonReaderForReceiveCommandResponse(differentMessageID, "result", 0);

    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, jsonReader, newTempFile(), () -> {}, () -> true);

    protocol.receiveCommandResponse(messageID);
  }

  @Test
  public void testReceiveCommandResponseWithInvalidType() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Expected response's \"type\" to be one of");

    int messageID = 123;
    InputStream jsonReader =
        createMockJsonReaderForReceiveCommandResponse(messageID, "INVALID RESPONSE TYPE", 0);

    WorkerProcessProtocol.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream, jsonReader, newTempFile(), () -> {}, () -> true);

    protocol.receiveCommandResponse(messageID);
  }

  @Test
  public void testCloseSender() throws IOException {
    AtomicBoolean cleanedUp = new AtomicBoolean(false);
    WorkerProcessProtocolZero.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream,
            inputStream("[]"),
            newTempFile(),
            () -> cleanedUp.set(true),
            () -> true);

    // write an opening bracket now, so the writer doesn't throw due to invalid JSON when it goes
    // to write the closing bracket
    protocol.getProcessStdinWriter().beginArray();

    // add an opening bracket and consume it now, so that the reader doesn't throw due to invalid
    // JSON when it goes to read the closing bracket
    protocol.getProcessStdoutReader().beginArray();

    protocol.close();

    String expectedJson = "]";
    assertThat(dummyOutputStream.toString(), Matchers.endsWith(expectedJson));
    assertTrue(cleanedUp.get());
  }

  @Test
  public void testProcessIsStillDestroyedEvenIfErrorOccursWhileClosingStreams() throws IOException {
    AtomicBoolean cleanedUp = new AtomicBoolean(false);
    WorkerProcessProtocolZero.CommandSender protocol =
        new WorkerProcessProtocolZero.CommandSender(
            dummyOutputStream,
            inputStream("invalid JSON"),
            newTempFile(),
            () -> cleanedUp.set(true),
            () -> true);

    // write an opening bracket now, so the writer doesn't throw due to invalid JSON when it goes
    // to write the closing bracket
    protocol.getProcessStdinWriter().beginArray();

    try {
      protocol.close();
      fail("Expected and IOException");
    } catch (IOException e) {
      assertThat(e.getMessage(), Matchers.containsString("malformed JSON"));
      // assert that process was still destroyed despite the exception
      assertTrue(cleanedUp.get());
    }
  }

  private Path newTempFile() throws IOException {
    return temporaryPaths.newFile();
  }

  private static ByteArrayInputStream inputStream(String string) {
    return new ByteArrayInputStream(string.getBytes(StandardCharsets.UTF_8));
  }
}
