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

package com.facebook.buck.downwardapi.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EndEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.ExternalEvent;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downward.model.StepEvent;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Json implementation of Downward API Protocol. */
public enum JsonDownwardProtocol implements DownwardProtocol {
  INSTANCE;

  private static final Logger LOG = Logger.get(JsonDownwardProtocol.class);

  private static final DownwardProtocolType PROTOCOL_TYPE = DownwardProtocolType.JSON;
  private static final String PROTOCOL_ID = PROTOCOL_TYPE.getProtocolId();

  private static final int MAX_NESTED_TOOLS_WITHOUT_EVENTS = 10;
  // up to 5 digit size = 99_999 -> less than 100K
  private static final int MAX_MESSAGE_SIZE_LENGTH_IN_CHARS =
      5 + DownwardProtocolUtils.DELIMITER.length();

  private final JsonFormat.Parser parser = JsonFormat.parser();
  private final JsonFormat.Printer printer = JsonFormat.printer();

  @Override
  public void write(EventTypeMessage eventType, AbstractMessage message, OutputStream outputStream)
      throws IOException {
    DownwardProtocolUtils.checkMessageType(eventType, message);
    synchronized (this) {
      writeJson(eventType, outputStream);
      writeJson(message, outputStream);
    }
  }

  @Override
  public EventTypeMessage.EventType readEventType(InputStream inputStream) throws IOException {
    String json = readJsonObjectAsString(inputStream);
    Message.Builder builder = EventTypeMessage.newBuilder();
    parser.merge(json, builder);
    return ((EventTypeMessage) builder.build()).getEventType();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends AbstractMessage> T readEvent(
      InputStream inputStream, EventTypeMessage.EventType eventType) throws IOException {
    String json = readJsonObjectAsString(inputStream);
    Message.Builder builder = getMessageBuilder(eventType);
    parser.merge(json, builder);
    return (T) builder.build();
  }

  @Override
  public DownwardProtocolType getProtocolType() {
    return PROTOCOL_TYPE;
  }

  private String readJsonObjectAsString(InputStream inputStream) throws IOException {
    int length =
        DownwardProtocolUtils.readFromStream(
            inputStream, MAX_MESSAGE_SIZE_LENGTH_IN_CHARS, s -> toLength(inputStream, s, 1));

    byte[] buffer = new byte[length];
    int bytesRead = inputStream.read(buffer);
    if (bytesRead != length) {
      throw new IOException(
          "Expected to read "
              + length
              + " bytes, but it was read only "
              + bytesRead
              + " bytes instead.");
    }
    return new String(buffer, UTF_8);
  }

  private int toLength(InputStream inputStream, String s, int depth)
      throws InvalidDownwardProtocolException {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException numberFormatException) {
      if (depth >= MAX_NESTED_TOOLS_WITHOUT_EVENTS) {
        throw new InvalidDownwardProtocolException(
            String.format(
                "Reached max number(%s) of allowed downward api nested tools without any events sent",
                MAX_NESTED_TOOLS_WITHOUT_EVENTS));
      }

      // maybe we just read a protocol id
      if (PROTOCOL_ID.equals(s)) {
        LOG.info("Another tool wants to establish protocol over json");
        return readLengthFromTheNestedTool(inputStream, depth);
      } else if (s.equals(DownwardProtocolType.BINARY.getProtocolId())) {
        throw new InvalidDownwardProtocolException(
            "Invoked tool wants to change downward protocol from json to binary one. It is not supported!");
      }

      throw new InvalidDownwardProtocolException(
          String.format(
              "Exception parsing integer number representing json message length: %s",
              numberFormatException.getMessage()));
    }
  }

  private int readLengthFromTheNestedTool(InputStream inputStream, int depth) {
    try {
      return DownwardProtocolUtils.readFromStream(
          inputStream, MAX_MESSAGE_SIZE_LENGTH_IN_CHARS, s -> toLength(inputStream, s, depth + 1));
    } catch (NumberFormatException e) {
      // expected integer number, but got something else.
      throw new InvalidDownwardProtocolException(
          String.format(
              "Exception parsing integer number representing json message length: %s",
              e.getMessage()));
    } catch (IOException e) {
      throw new InvalidDownwardProtocolException("Exception during reading an integer number", e);
    }
  }

  private Message.Builder getMessageBuilder(EventTypeMessage.EventType eventType) {
    switch (eventType) {
      case CONSOLE_EVENT:
        return ConsoleEvent.newBuilder();

      case LOG_EVENT:
        return LogEvent.newBuilder();

      case STEP_EVENT:
        return StepEvent.newBuilder();

      case CHROME_TRACE_EVENT:
        return ChromeTraceEvent.newBuilder();

      case END_EVENT:
        return EndEvent.newBuilder();

      case EXTERNAL_EVENT:
        return ExternalEvent.newBuilder();

      case RESULT_EVENT:
        return ResultEvent.newBuilder();

      case PIPELINE_FINISHED_EVENT:
        return PipelineFinishedEvent.newBuilder();

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Unexpected value: " + eventType);
    }
  }

  private void writeJson(MessageOrBuilder messageOrBuilder, OutputStream outputStream)
      throws IOException {
    String json = printer.print(messageOrBuilder);
    byte[] bytes = json.getBytes(UTF_8);
    // write json length
    outputStream.write(String.valueOf(bytes.length).getBytes(UTF_8));
    // write delimiter
    DownwardProtocolUtils.writeDelimiter(outputStream);
    // write json
    outputStream.write(bytes);
  }
}
