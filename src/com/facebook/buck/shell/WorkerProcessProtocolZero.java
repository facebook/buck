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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkerProcessProtocolZero implements WorkerProcessProtocol {

  private static final String TYPE_HANDSHAKE = "handshake";
  private static final String TYPE_COMMAND = "command";
  private static final String TYPE_RESULT = "result";
  private static final String TYPE_ERROR = "error";
  private static final String PROTOCOL_VERSION = "0";

  private final JsonWriter processStdinWriter;
  private final JsonReader processStdoutReader;
  private final Path stdErr;
  private final Runnable cleanUp;
  private boolean isClosed = false;

  public WorkerProcessProtocolZero(
      JsonWriter processStdinWriter,
      JsonReader processStdoutReader,
      Path stdErr,
      Runnable cleanUp) {
    this.processStdinWriter = processStdinWriter;
    this.processStdoutReader = processStdoutReader;
    this.stdErr = stdErr;
    this.cleanUp = cleanUp;
  }

  /*
   Sends a message that looks like this:
     [
       {
         id: <handshakeID>,
         type: 'handshake',
         protocol_version: '0',
         capabilities: []
       }
  */
  @Override
  public void sendHandshake(int handshakeID) throws IOException {
    processStdinWriter.beginArray();
    processStdinWriter.beginObject();
    processStdinWriter.name("id").value(handshakeID);
    processStdinWriter.name("type").value(TYPE_HANDSHAKE);
    processStdinWriter.name("protocol_version").value(PROTOCOL_VERSION);
    processStdinWriter.name("capabilities").beginArray().endArray();
    processStdinWriter.endObject();
    processStdinWriter.flush();
  }

  /*
   Expects a message that looks like this:
     [
       {
         id: <handshakeID>,
         type: 'handshake',
         protocol_version: '0',
         capabilities: []
       }
  */
  @Override
  public void receiveHandshake(int handshakeID) throws IOException {
    int id = -1;
    String type = "";
    String protocolVersion = "";

    try {
      processStdoutReader.beginArray();
      processStdoutReader.beginObject();
      while (processStdoutReader.hasNext()) {
        String property = processStdoutReader.nextName();
        if (property.equals("id")) {
          id = processStdoutReader.nextInt();
        } else if (property.equals("type")) {
          type = processStdoutReader.nextString();
        } else if (property.equals("protocol_version")) {
          protocolVersion = processStdoutReader.nextString();
        } else if (property.equals("capabilities")) {
          try {
            processStdoutReader.beginArray();
            processStdoutReader.endArray();
          } catch (IllegalStateException e) {
            throw new HumanReadableException(
                "Expected handshake response's \"capabilities\" to " + "be an empty array.");
          }
        } else {
          processStdoutReader.skipValue();
        }
      }
      processStdoutReader.endObject();
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Error receiving handshake response from external process.\n"
              + "Stderr from external process:\n%s",
          getStdErrorOutput());
    }

    if (id != handshakeID) {
      throw new HumanReadableException(
          String.format(
              "Expected handshake response's \"id\" value " + "to be \"%d\", got \"%d\" instead.",
              handshakeID, id));
    }
    if (!type.equals(TYPE_HANDSHAKE)) {
      throw new HumanReadableException(
          String.format(
              "Expected handshake response's \"type\" " + "to be \"%s\", got \"%s\" instead.",
              TYPE_HANDSHAKE, type));
    }
    if (!protocolVersion.equals(PROTOCOL_VERSION)) {
      throw new HumanReadableException(
          String.format(
              "Expected handshake response's "
                  + "\"protocol_version\" to be \"%s\", got \"%s\" instead.",
              PROTOCOL_VERSION, protocolVersion));
    }
  }

  /*
    Sends a message that looks like this:
      ,{
        id: <id>,
        type: 'command',
        args_path: <argsPath>,
        stdout_path: <stdoutPath>,
        stderr_path: <stderrPath>,
      }
  */
  @Override
  public void sendCommand(int messageID, WorkerProcessCommand command) throws IOException {
    processStdinWriter.beginObject();
    processStdinWriter.name("id").value(messageID);
    processStdinWriter.name("type").value(TYPE_COMMAND);
    processStdinWriter.name("args_path").value(command.getArgsPath().toString());
    processStdinWriter.name("stdout_path").value(command.getStdOutPath().toString());
    processStdinWriter.name("stderr_path").value(command.getStdErrPath().toString());
    processStdinWriter.endObject();
    processStdinWriter.flush();
  }

  /*
    Expects a message that looks like this:
      ,{
        id: <id>,
        type: 'command',
        args_path: <argsPath>,
        stdout_path: <stdoutPath>,
        stderr_path: <stderrPath>,
      }
  */
  @Override
  public WorkerProcessCommand receiveCommand(int messageID) throws IOException {
    int id = -1;
    String type = "";
    String argsPath = "";
    String stdoutPath = "";
    String stderrPath = "";
    try {
      processStdoutReader.beginObject();
      while (processStdoutReader.hasNext()) {
        String property = processStdoutReader.nextName();
        if (property.equals("id")) {
          id = processStdoutReader.nextInt();
        } else if (property.equals("type")) {
          type = processStdoutReader.nextString();
        } else if (property.equals("args_path")) {
          argsPath = processStdoutReader.nextString();
        } else if (property.equals("stdout_path")) {
          stdoutPath = processStdoutReader.nextString();
        } else if (property.equals("stderr_path")) {
          stderrPath = processStdoutReader.nextString();
        } else {
          processStdoutReader.skipValue();
        }
      }
      processStdoutReader.endObject();
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Error receiving command from external process.\nStderr from external process:\n%s",
          getStdErrorOutput());
    }

    if (id != messageID) {
      throw new HumanReadableException(
          String.format(
              "Expected command's \"id\" value to be " + "\"%d\", got \"%d\" instead.",
              messageID, id));
    }
    if (!type.equals(TYPE_COMMAND)) {
      throw new HumanReadableException(
          String.format(
              "Expected command's \"type\" " + "to be \"%s\", got \"%s\" instead.",
              TYPE_COMMAND, type));
    }

    return WorkerProcessCommand.of(
        Paths.get(argsPath), Paths.get(stdoutPath), Paths.get(stderrPath));
  }

  /*
    Sends a message that looks like this if the job was successful:
      ,{
        id: <messageID>,
        type: 'result',
        exit_code: 0
      }

    of a message that looks like this if message was correct but job failed due to various reasons:
      ,{
        id: <messageID>,
        type: 'result',
        exit_code: <exitCode>
      }

    or a message that looks like this if process received a message type it cannot
    interpret:
      ,{
        id: <messageID>,
        type: 'error',
        exit_code: 1
      }

    or a message that looks like this if process received a valid message type but other
    attributes of the message were in an inconsistent state:
      ,{
        id: <messageID>,
        type: 'error',
        exit_code: 2
      }
  */
  @Override
  public void sendCommandResponse(int messageID, String type, int exitCode) throws IOException {
    if (!type.equals(TYPE_RESULT) && !type.equals(TYPE_ERROR)) {
      throw new HumanReadableException(
          String.format(
              "Expected response's \"type\" " + "to be one of [\"%s\",\"%s\"], got \"%s\" instead.",
              TYPE_RESULT, TYPE_ERROR, type));
    }
    if (type.equals(TYPE_ERROR) && exitCode != 1 && exitCode != 2) {
      throw new HumanReadableException(
          String.format(
              "For response with type "
                  + "\"%s\" exit code is expected to be 1 or 2, got %d instead.",
              type, exitCode));
    }
    processStdinWriter.beginObject();
    processStdinWriter.name("id").value(messageID);
    processStdinWriter.name("type").value(type);
    processStdinWriter.name("exit_code").value(exitCode);
    processStdinWriter.endObject();
    processStdinWriter.flush();
  }

  /*
    Expects a message that looks like this if the job was successful:
      ,{
        id: <messageID>,
        type: 'result',
        exit_code: 0
      }

    of a message that looks like this if message was correct but job failed due to various reasons:
      ,{
        id: <messageID>,
        type: 'result',
        exit_code: <exitCode>
      }

    or a message that looks like this if the external tool received a message type it cannot
    interpret:
      ,{
        id: <messageID>,
        type: 'error',
        exit_code: 1
      }

    or a message that looks like this if the external tool received a valid message type but other
    attributes of the message were in an inconsistent state:
      ,{
        id: <messageID>,
        type: 'error',
        exit_code: 2
      }
  */
  @Override
  public int receiveCommandResponse(int messageID) throws IOException {
    int id = -1;
    int exitCode = -1;
    String type = "";

    try {
      processStdoutReader.beginObject();
      while (processStdoutReader.hasNext()) {
        String property = processStdoutReader.nextName();
        if (property.equals("id")) {
          id = processStdoutReader.nextInt();
        } else if (property.equals("type")) {
          type = processStdoutReader.nextString();
        } else if (property.equals("exit_code")) {
          exitCode = processStdoutReader.nextInt();
        } else {
          processStdoutReader.skipValue();
        }
      }
      processStdoutReader.endObject();
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Error receiving command response from external process.\n"
              + "Stderr from external process:\n%s",
          getStdErrorOutput());
    }

    if (id != messageID) {
      throw new HumanReadableException(
          String.format(
              "Expected response's \"id\" value to be " + "\"%d\", got \"%d\" instead.",
              messageID, id));
    }
    if (!type.equals(TYPE_RESULT) && !type.equals(TYPE_ERROR)) {
      throw new HumanReadableException(
          String.format(
              "Expected response's \"type\" " + "to be one of [\"%s\",\"%s\"], got \"%s\" instead.",
              TYPE_RESULT, TYPE_ERROR, type));
    }
    return exitCode;
  }

  /*
   Sends the closing bracket for the JSON array and expects a response containing a closing
   bracket as well. Closes the input and output streams and destroys the process.
  */
  @Override
  public void close() throws IOException {
    Preconditions.checkArgument(
        !isClosed,
        "%s (%d) has been already closed",
        getClass().getSimpleName(),
        System.identityHashCode(this));
    try {
      processStdinWriter.endArray();
      processStdinWriter.close();
      processStdoutReader.endArray();
      processStdoutReader.close();
    } finally {
      cleanUp.run();
      isClosed = true;
    }
  }

  private String getStdErrorOutput() throws IOException {
    StringBuilder sb = new StringBuilder();
    try (InputStream inputStream = Files.newInputStream(stdErr)) {
      BufferedReader errorReader = new BufferedReader(new InputStreamReader(inputStream));
      while (errorReader.ready()) {
        sb.append("\t").append(errorReader.readLine()).append("\n");
      }
    }
    return sb.toString();
  }
}
