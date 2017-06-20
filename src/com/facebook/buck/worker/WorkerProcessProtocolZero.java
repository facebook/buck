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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class WorkerProcessProtocolZero {
  public static class CommandSender implements WorkerProcessProtocol.CommandSender {
    private final JsonWriter processStdinWriter;
    private final JsonReader processStdoutReader;
    private final Optional<Path> stdErr;
    private final Runnable onClose;
    private boolean isClosed = false;

    public CommandSender(
        OutputStream processStdin, InputStream processStdout, Path stdErr, Runnable onClose) {
      this.processStdinWriter =
          new JsonWriter(new BufferedWriter(new OutputStreamWriter(processStdin)));
      this.processStdoutReader =
          new JsonReader(new BufferedReader(new InputStreamReader(processStdout)));
      this.stdErr = Optional.of(stdErr);
      this.onClose = onClose;
    }

    @VisibleForTesting
    JsonReader getProcessStdoutReader() {
      return processStdoutReader;
    }

    @VisibleForTesting
    JsonWriter getProcessStdinWriter() {
      return processStdinWriter;
    }

    @Override
    public void handshake(int messageId) throws IOException {
      sendHandshake(processStdinWriter, messageId);
      receiveHandshake(processStdoutReader, messageId, stdErr);
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
    public void send(int messageId, WorkerProcessCommand command) throws IOException {
      processStdinWriter.beginObject();
      processStdinWriter.name("id").value(messageId);
      processStdinWriter.name("type").value(TYPE_COMMAND);
      processStdinWriter.name("args_path").value(command.getArgsPath().toString());
      processStdinWriter.name("stdout_path").value(command.getStdOutPath().toString());
      processStdinWriter.name("stderr_path").value(command.getStdErrPath().toString());
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
            getStdErrorOutput(stdErr));
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
                "Expected response's \"type\" "
                    + "to be one of [\"%s\",\"%s\"], got \"%s\" instead.",
                TYPE_RESULT, TYPE_ERROR, type));
      }
      return exitCode;
    }

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
        onClose.run();
        isClosed = true;
      }
    }
  }

  public static class CommandReceiver implements WorkerProcessProtocol.CommandReceiver {

    private final JsonWriter processStdinWriter;
    private final JsonReader processStdoutReader;
    private final Optional<Path> stdErr;
    private boolean isClosed = false;

    public CommandReceiver(OutputStream processStdin, InputStream processStdout) {
      this.processStdinWriter =
          new JsonWriter(new BufferedWriter(new OutputStreamWriter(processStdin)));
      this.processStdoutReader =
          new JsonReader(new BufferedReader(new InputStreamReader(processStdout)));
      this.stdErr = Optional.empty();
    }

    @VisibleForTesting
    JsonReader getProcessStdoutReader() {
      return processStdoutReader;
    }

    @VisibleForTesting
    JsonWriter getProcessStdinWriter() {
      return processStdinWriter;
    }

    @Override
    public void handshake(int messageId) throws IOException {
      sendHandshake(processStdinWriter, messageId);
      receiveHandshake(processStdoutReader, messageId, Optional.empty());
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
    public WorkerProcessCommand receiveCommand(int messageId) throws IOException {
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
            getStdErrorOutput(stdErr));
      }

      if (id != messageId) {
        throw new HumanReadableException(
            String.format(
                "Expected command's \"id\" value to be " + "\"%d\", got \"%d\" instead.",
                messageId, id));
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
    public void sendResponse(int messageId, String type, int exitCode) throws IOException {
      if (!type.equals(TYPE_RESULT) && !type.equals(TYPE_ERROR)) {
        throw new HumanReadableException(
            String.format(
                "Expected response's \"type\" "
                    + "to be one of [\"%s\",\"%s\"], got \"%s\" instead.",
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
      processStdinWriter.name("id").value(messageId);
      processStdinWriter.name("type").value(type);
      processStdinWriter.name("exit_code").value(exitCode);
      processStdinWriter.endObject();
      processStdinWriter.flush();
    }

    @Override
    public boolean shouldClose() throws IOException {
      return JsonToken.END_ARRAY == processStdoutReader.peek();
    }

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
        isClosed = true;
      }
    }
  }

  private static final String TYPE_HANDSHAKE = "handshake";
  private static final String TYPE_COMMAND = "command";
  private static final String PROTOCOL_VERSION = "0";
  private static final String TYPE_RESULT = "result";
  private static final String TYPE_ERROR = "error";

  /*
   Sends a message that looks like this:
     [
       {
         id: 0,
         type: 'handshake',
         protocol_version: '0',
         capabilities: []
       }
  */
  private static void sendHandshake(JsonWriter writer, int messageId) throws IOException {
    writer.beginArray();
    writer.beginObject();
    writer.name("id").value(messageId);
    writer.name("type").value(TYPE_HANDSHAKE);
    writer.name("protocol_version").value(PROTOCOL_VERSION);
    writer.name("capabilities").beginArray().endArray();
    writer.endObject();
    writer.flush();
  }

  /*
   Expects a message that looks like this:
     [
       {
         id: 0,
         type: 'handshake',
         protocol_version: '0',
         capabilities: []
       }
  */
  private static void receiveHandshake(JsonReader reader, int messageId, Optional<Path> stdErr)
      throws IOException {
    int id = -1;
    String type = "";
    String protocolVersion = "";

    try {
      reader.beginArray();
      reader.beginObject();
      while (reader.hasNext()) {
        String property = reader.nextName();
        if (property.equals("id")) {
          id = reader.nextInt();
        } else if (property.equals("type")) {
          type = reader.nextString();
        } else if (property.equals("protocol_version")) {
          protocolVersion = reader.nextString();
        } else if (property.equals("capabilities")) {
          try {
            reader.beginArray();
            reader.endArray();
          } catch (IllegalStateException e) {
            throw new HumanReadableException(
                "Expected handshake response's \"capabilities\" to " + "be an empty array.");
          }
        } else {
          reader.skipValue();
        }
      }
      reader.endObject();
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Error receiving handshake response from external process.\n"
              + "Stderr from external process:\n%s",
          getStdErrorOutput(stdErr));
    }

    if (id != messageId) {
      throw new HumanReadableException(
          String.format(
              "Expected handshake response's \"id\" value " + "to be \"%d\", got \"%d\" instead.",
              messageId, id));
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

  private static String getStdErrorOutput(Optional<Path> stdErr) throws IOException {
    if (!stdErr.isPresent()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    try (InputStream inputStream = Files.newInputStream(stdErr.get());
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(inputStream))) {
      while (errorReader.ready()) {
        sb.append("\t").append(errorReader.readLine()).append("\n");
      }
    }
    return sb.toString();
  }
}
