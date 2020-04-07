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
import com.facebook.buck.util.json.ObjectMappers;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Test;

public class NamedPipesTest {

  private static final Logger LOG = Logger.get(NamedPipesTest.class);

  private static final String DELIMITER = "^^^";

  @BeforeClass
  public static void beforeClass() throws Exception {
    // invoke initialize of ObjectMappers.WRITER and ObjectMappers.READER that happens in
    // static-block
    Class.forName(ObjectMappers.class.getCanonicalName());
  }

  /**
   * Test creates a named pipe. Then writes to it 3 messages with 1 second delay. Reader executes as
   * a separate thread.
   *
   * <p>In the end test verifies that message that were sent was received in about 1s.
   */
  @Test
  public void testNamedPipes() {
    NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    List<ReceivedNamedPipeJsonMessage> receivedMessages = new ArrayList<>();
    String namedPipePath = null;
    try (NamedPipe namedPipe = namedPipeFactory.create()) {
      LOG.info("Named pipe created: " + namedPipe);
      namedPipePath = namedPipe.getName();
      executorService.execute(readFromNamedPipeRunnable(namedPipePath, receivedMessages));

      try (DataOutputStream outputStream = new DataOutputStream(namedPipe.getOutputStream())) {
        LOG.info("Starting write messages into a named pipe!");
        writeToNamedPipe(outputStream, "Hello pipe reader!");
        TimeUnit.SECONDS.sleep(1);
        writeToNamedPipe(outputStream, "Hello again!");
        TimeUnit.SECONDS.sleep(1);
        writeToNamedPipe(outputStream, "Bye!");
      } catch (IOException e) {
        LOG.error(e, "Can't write into a named pipe: " + namedPipePath);
      } finally {
        executorService.shutdown();
        if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      }
    } catch (Exception e) {
      LOG.error(e, "Can't create a named pipe.");
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

  private static Runnable readFromNamedPipeRunnable(
      String namedPipePath, List<ReceivedNamedPipeJsonMessage> receivedMessages) {

    return new Runnable() {
      @Override
      public void run() {
        NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
        try (NamedPipe namedPipe = namedPipeFactory.connect(Paths.get(namedPipePath));
            InputStream inputStream = namedPipe.getInputStream()) {
          LOG.info("Read named pipe: " + namedPipe);
          processMessages(inputStream);
          LOG.info("Finishing reader thread!");
        } catch (IOException e) {
          LOG.error(e, "Can't read from a named pipe: " + namedPipePath);
        }
      }

      private void processMessages(InputStream inputStream) throws IOException {
        int read;
        StringBuilder buffer = new StringBuilder();
        while ((read = inputStream.read()) != -1) {
          buffer.append((char) read);
          if (isEndOfTheObject(buffer)) {
            String message = buffer.toString();
            buffer.setLength(0);
            receivedMessages.add(serialize(message));
          }
        }
      }

      private boolean isEndOfTheObject(StringBuilder sb) {
        int length = sb.length();
        if (length < DELIMITER.length()) {
          return false;
        }
        for (int i = 0; i < DELIMITER.length(); i++) {
          if (DELIMITER.charAt(i) != sb.charAt(length - DELIMITER.length() + i)) {
            return false;
          }
        }
        return true;
      }

      private ReceivedNamedPipeJsonMessage serialize(String message) throws IOException {
        NamedPipeJsonMessage jsonMessage =
            ObjectMappers.readValue(message, NamedPipeJsonMessage.class);
        LOG.info("json message received : " + jsonMessage);
        return ImmutableReceivedNamedPipeJsonMessage.ofImpl(Instant.now(), jsonMessage);
      }
    };
  }

  private static void writeToNamedPipe(DataOutputStream outputStream, String message)
      throws IOException {
    NamedPipeJsonMessage namedPipeJsonMessage =
        ImmutableNamedPipeJsonMessage.ofImpl(Instant.now().toString(), message);
    ObjectMappers.WRITER.writeValue((DataOutput) outputStream, namedPipeJsonMessage);
    outputStream.writeUTF(DELIMITER);
    outputStream.flush();
  }
}
