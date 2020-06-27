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

package com.facebook.buck.io.namedpipes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class NamedPipesTest {

  private static final Logger LOG = Logger.get(NamedPipesTest.class);

  @BeforeClass
  public static void beforeClass() throws Exception {
    // invoke initialize of ObjectMappers.WRITER and ObjectMappers.READER that happens in
    // static-block
    Class.forName(ObjectMappers.class.getCanonicalName());
  }

  public Object getProtocols() {
    return DownwardProtocolType.values();
  }

  /**
   * Test creates a named pipe. Then writes to it 3 messages with 1 second delay. Reader executes as
   * a separate thread.
   *
   * <p>In the end test verifies that message that were sent was received in about 1s.
   */
  @Parameters(method = "getProtocols")
  @Test
  public void testNamedPipes(DownwardProtocolType protocolType) throws InterruptedException {
    NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
    ExecutorService executorService = MostExecutors.newSingleThreadExecutor("named_pipe_reader");
    List<ReceivedNamedPipeJsonMessage> receivedMessages = new ArrayList<>();
    String namedPipePath = null;
    try (NamedPipe namedPipe = namedPipeFactory.create()) {
      namedPipePath = namedPipe.getName();
      LOG.info("Named pipe created: %s", namedPipePath);
      Future<?> future =
          executorService.submit(readFromNamedPipeRunnable(namedPipePath, receivedMessages, 3));
      writeIntoNamedPipe(protocolType, namedPipe);
      future.get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error(e, "Can't create a named pipe.");
    } finally {
      MostExecutors.shutdown(executorService, 1, TimeUnit.SECONDS);
    }

    assertNotNull("Named pipe has not been created!", namedPipePath);
    assertFalse("Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipePath)));

    assertEquals(
        "Expected that reader was able to read 3 messages over named pipe!",
        3,
        receivedMessages.size());
    for (ReceivedNamedPipeJsonMessage message : receivedMessages) {
      Instant receivedInstant = message.getReceivedTime();
      Instant sentInstant = Instant.parse(message.getMessage().getTimeSent());

      assertFalse(
          "Receive timestamp: "
              + receivedInstant
              + " must be after or equals send timestamp: "
              + sentInstant,
          receivedInstant.isBefore(sentInstant));
      assertFalse(
          "Receive timestamp: "
              + receivedInstant
              + " must be not more than 1s different from the send timestamp: "
              + sentInstant,
          receivedInstant.minus(Duration.ofSeconds(1)).isAfter(sentInstant));
    }
  }

  private void writeIntoNamedPipe(DownwardProtocolType protocolType, NamedPipe namedPipe) {
    try (OutputStream outputStream = namedPipe.getOutputStream()) {
      LOG.info("Starting write messages into a named pipe: %s!", namedPipe.getName());
      protocolType.writeDelimitedTo(outputStream);
      DownwardProtocol downwardProtocol = protocolType.getDownwardProtocol();
      writeToNamedPipe(
          downwardProtocol, EventType.CONSOLE_EVENT, "Hello pipe reader!", outputStream);
      writeToNamedPipe(downwardProtocol, EventType.LOG_EVENT, "Hello again!", outputStream);
      writeToNamedPipe(downwardProtocol, EventType.STEP_EVENT, "Bye!", outputStream);
    } catch (IOException e) {
      LOG.error(e, "Can't write into a named pipe: %s", namedPipe.getName());
    }
  }

  private static Runnable readFromNamedPipeRunnable(
      String namedPipePath,
      List<ReceivedNamedPipeJsonMessage> receivedMessages,
      int expectedMessageSize) {

    return new Runnable() {
      @Override
      public void run() {
        NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
        try (NamedPipe namedPipe = namedPipeFactory.connect(Paths.get(namedPipePath));
            InputStream inputStream = namedPipe.getInputStream()) {
          LOG.info("Starting reading from named pipe: %s", namedPipe);
          DownwardProtocol downwardProtocol = DownwardProtocolType.readProtocol(inputStream);
          while (receivedMessages.size() < expectedMessageSize) {
            try {
              EventType eventType = downwardProtocol.readEventType(inputStream);
              String originalMessage =
                  parseOriginalMessage(inputStream, downwardProtocol, eventType);
              receivedMessages.add(serialize(originalMessage));
            } catch (IOException e) {
              LOG.info("End of the stream!");
              break;
            }
          }
          LOG.info("Finishing reader thread!");
        } catch (IOException e) {
          LOG.error(e, "Cannot read from a named pipe: %s", namedPipePath);
        }
      }

      private String parseOriginalMessage(
          InputStream inputStream, DownwardProtocol downwardProtocol, EventType eventType)
          throws IOException {
        switch (eventType) {
          case CONSOLE_EVENT:
            ConsoleEvent consoleEvent = downwardProtocol.readEvent(inputStream, eventType);
            return consoleEvent.getMessage();
          case LOG_EVENT:
            LogEvent logEvent = downwardProtocol.readEvent(inputStream, eventType);
            return logEvent.getMessage();
          case STEP_EVENT:
            StepEvent stepEvent = downwardProtocol.readEvent(inputStream, eventType);
            return stepEvent.getDescription();
        }
        throw new IllegalStateException("Not yet implemented value: " + eventType);
      }

      private ReceivedNamedPipeJsonMessage serialize(String message) throws IOException {
        NamedPipeJsonMessage jsonMessage =
            ObjectMappers.readValue(message, NamedPipeJsonMessage.class);
        LOG.info("json message received : %s", jsonMessage);
        return ImmutableReceivedNamedPipeJsonMessage.ofImpl(Instant.now(), jsonMessage);
      }
    };
  }

  private static void writeToNamedPipe(
      DownwardProtocol downwardProtocol,
      EventType eventType,
      String message,
      OutputStream outputStream)
      throws IOException {

    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(eventType).build();

    NamedPipeJsonMessage namedPipeJsonMessage =
        ImmutableNamedPipeJsonMessage.ofImpl(Instant.now().toString(), message);
    StringWriter stringWriter = new StringWriter();
    ObjectMappers.WRITER.writeValue(stringWriter, namedPipeJsonMessage);
    String namedPipeJsonMessageString = stringWriter.toString();

    AbstractMessage event = createEvent(eventType, namedPipeJsonMessageString);

    // write event_type
    downwardProtocol.write(eventTypeMessage, outputStream);
    // write event
    downwardProtocol.write(event, outputStream);
    outputStream.flush();
  }

  private static AbstractMessage createEvent(
      EventType eventType, String namedPipeJsonMessageString) {
    switch (eventType) {
      case CONSOLE_EVENT:
        return ConsoleEvent.newBuilder().setMessage(namedPipeJsonMessageString).build();
      case LOG_EVENT:
        return LogEvent.newBuilder().setMessage(namedPipeJsonMessageString).build();
      case STEP_EVENT:
        return StepEvent.newBuilder().setDescription(namedPipeJsonMessageString).build();
    }
    throw new IllegalStateException("Unexpected value: " + eventType);
  }
}
