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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableMap;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkerProcessProtocolZeroTest {

  @Rule
  public TemporaryPaths temporaryPaths = new TemporaryPaths();

  private ProcessExecutor fakeProcessExecutor;
  private ProcessExecutor.LaunchedProcess fakeLaunchedProcess;
  private FakeProcess fakeProcess;
  private JsonWriter dummyJsonWriter;
  private JsonReader dummyJsonReader;

  @Before
  public void setUp() throws IOException {
    ProcessExecutorParams fakeParams = ProcessExecutorParams.ofCommand("");
    fakeProcess = new FakeProcess(0);
    fakeProcessExecutor = new FakeProcessExecutor(ImmutableMap.of(fakeParams, fakeProcess));
    fakeLaunchedProcess = fakeProcessExecutor.launchProcess(fakeParams);
    dummyJsonWriter = new JsonWriter(new StringWriter());
    dummyJsonReader = new JsonReader(new StringReader(""));
  }

  @Test
  public void testSendHandshake() throws IOException {
    StringWriter jsonSentToWorkerProcess = new StringWriter();
    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        new JsonWriter(jsonSentToWorkerProcess),
        dummyJsonReader,
        newTempFile());

    int handshakeID = 123;
    protocol.sendHandshake(handshakeID);
    String expectedJson = String.format(
        "[{\"id\":%d,\"type\":\"handshake\",\"protocol_version\":\"0\",\"capabilities\":[]}",
        handshakeID);
    assertThat(jsonSentToWorkerProcess.toString(), Matchers.containsString(expectedJson));
  }

  @Test
  public void testSendCommand() throws IOException {
    StringWriter jsonSentToWorkerProcess = new StringWriter();
    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        new JsonWriter(jsonSentToWorkerProcess),
        dummyJsonReader,
        newTempFile());

    int messageID = 123;
    Path argsPath = Paths.get("args");
    Path stdoutPath = Paths.get("stdout");
    Path stderrPath = Paths.get("stderr");
    protocol.sendCommand(messageID, argsPath, stdoutPath, stderrPath);
    String expectedJson = String.format(
        "{\"id\":%d,\"type\":\"command\"," +
        "\"args_path\":\"%s\",\"stdout_path\":\"%s\",\"stderr_path\":\"%s\"}",
        messageID,
        argsPath.toString(),
        stdoutPath.toString(),
        stderrPath.toString());
    assertThat(jsonSentToWorkerProcess.toString(), Matchers.containsString(expectedJson));
  }

  private JsonReader createMockJsonReaderForReceiveHandshake(
      int handshakeID,
      String type,
      String protocolVersion) throws IOException {
    String jsonToBeRead = String.format(
        "[{\"id\":%d,\"type\":\"%s\",\"protocol_version\":\"%s\",\"capabilities\":[]}",
        handshakeID,
        type,
        protocolVersion);
    return new JsonReader(new StringReader(jsonToBeRead));
  }

  @Test
  public void testReceiveHandshake() throws IOException {
    int handshakeID = 123;
    JsonReader jsonReader = createMockJsonReaderForReceiveHandshake(handshakeID, "handshake", "0");

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    protocol.receiveHandshake(handshakeID);
  }

  @Test
  public void testReceiveHandshakeWithMalformedJSON() throws IOException {
    String malformedJson = "=^..^= meow";

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        new JsonReader(new StringReader(malformedJson)),
        newTempFile());

    try {
      protocol.receiveHandshake(123);
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), Matchers.containsString("Error receiving handshake response"));
    }
  }

  @Test
  public void testReceiveHandshakeWithIncorrectID() throws IOException {
    int handshakeID = 123;
    int differentHandshakeID = 456;
    JsonReader jsonReader = createMockJsonReaderForReceiveHandshake(
        differentHandshakeID,
        "handshake",
        "0");

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    try {
      protocol.receiveHandshake(handshakeID);
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              String.format(
                  "Expected handshake response's \"id\" value to be \"%d\"",
                  handshakeID)));
    }
  }

  @Test
  public void testReceiveHandshakeWithIncorrectType() throws IOException {
    int handshakeID = 123;
    JsonReader jsonReader = createMockJsonReaderForReceiveHandshake(
        handshakeID,
        "INCORRECT MESSAGE TYPE",
        "0");

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    try {
      protocol.receiveHandshake(handshakeID);
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "Expected handshake response's \"type\" to be \"handshake\""));
    }
  }

  @Test
  public void testReceiveHandshakeWithIncorrectProtocolVersion() throws IOException {
    int handshakeID = 123;
    JsonReader jsonReader = createMockJsonReaderForReceiveHandshake(
        handshakeID,
        "handshake",
        "BAD PROTOCOL VERSION");

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    try {
      protocol.receiveHandshake(handshakeID);
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "Expected handshake response's \"protocol_version\" to be \"0\""));
    }
  }

  private JsonReader createMockJsonReaderForReceiveCommandResponse(
      int messageID,
      String type,
      int exitCode) throws IOException {
    String jsonToBeRead = String.format(
        "{\"id\":%d,\"type\":\"%s\",\"exit_code\":%d}",
        messageID,
        type,
        exitCode);
    return new JsonReader(new StringReader(jsonToBeRead));
  }

  @Test
  public void testReceiveCommandResponse() throws IOException {
    int messageID = 123;
    JsonReader jsonReader = createMockJsonReaderForReceiveCommandResponse(messageID, "result", 0);

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    protocol.receiveCommandResponse(messageID);
  }

  @Test
  public void testReceiveCommandResponseWithMalformedJSON() throws IOException {
    String malformedJson = "><(((('> blub";

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        new JsonReader(new StringReader(malformedJson)),
        newTempFile());

    try {
      protocol.receiveCommandResponse(123);
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), Matchers.containsString("Error receiving command response"));
    }
  }

  @Test
  public void testReceiveCommandResponseWithIncorrectMessageID() throws IOException {
    int messageID = 123;
    int differentMessageID = 456;
    JsonReader jsonReader = createMockJsonReaderForReceiveCommandResponse(
        differentMessageID,
        "result",
        0);

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    try {
      protocol.receiveCommandResponse(messageID);
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              String.format("Expected response's \"id\" value to be \"%d\"", messageID)));
    }
  }

  @Test
  public void testReceiveCommandResponseWithInvalidType() throws IOException {
    int messageID = 123;
    JsonReader jsonReader = createMockJsonReaderForReceiveCommandResponse(
        messageID,
        "INVALID RESPONSE TYPE",
        0);

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        dummyJsonWriter,
        jsonReader,
        newTempFile());

    try {
      protocol.receiveCommandResponse(messageID);
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString("Expected response's \"type\" to be one of"));
    }
  }

  @Test
  public void testClose() throws IOException {
    StringWriter jsonSentToWorkerProcess = new StringWriter();
    JsonWriter writer = new JsonWriter(jsonSentToWorkerProcess);
    // write an opening bracket now, so the writer doesn't throw due to invalid JSON when it goes
    // to write the closing bracket
    writer.beginArray();

    // add an opening bracket and consume it now, so that the reader doesn't throw due to invalid
    // JSON when it goes to read the closing bracket
    JsonReader reader = new JsonReader(new StringReader("[]"));
    reader.beginArray();

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        writer,
        reader,
        newTempFile());

    protocol.close();

    String expectedJson = "]";
    assertThat(jsonSentToWorkerProcess.toString(), Matchers.endsWith(expectedJson));
    assertTrue(fakeProcess.isDestroyed());
  }

  @Test
  public void testProcessIsStillDestroyedEvenIfErrorOccursWhileClosingStreams() throws IOException {
    JsonWriter writer = new JsonWriter(new StringWriter());
    // write an opening bracket now, so the writer doesn't throw due to invalid JSON when it goes
    // to write the closing bracket
    writer.beginArray();

    JsonReader reader = new JsonReader(new StringReader("invalid JSON"));

    WorkerProcessProtocol protocol = new WorkerProcessProtocolZero(
        fakeProcessExecutor,
        fakeLaunchedProcess,
        writer,
        reader,
        newTempFile());

    try {
      protocol.close();
    } catch (IOException e) {
      assertThat(e.getMessage(), Matchers.containsString("malformed JSON"));
      // assert that process was still destroyed despite the exception
      assertTrue(fakeProcess.isDestroyed());
    }
  }

  private Path newTempFile() throws IOException {
    return temporaryPaths.newFile();
  }
}
