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

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Throwables;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

public class NamedPipesTest {

  private static Logger LOG = Logger.get(NamedPipesTest.class);

  /** used to signal end of the file for named pipe communication */
  private static final String EOF = "EOF_MAGIC_TOKEN";

  private static final String READ_MESSAGE_PREFIX = "receive time:";
  private static final String WRITE_MESSAGE_PREFIX = "send time:";

  /**
   * Test creates a named pipe. Then writes to it 3 messages with 1 second delay. Reader executes as
   * a separate thread.
   *
   * <p>End of file send as a separate magic token. In the end test verifies that message that were
   * sent was received in about 100ms.
   */
  @Test
  public void testNamedPipes() throws IOException {

    // TODO msemko@:
    Assume.assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));

    StringWriter stringWriter = new StringWriter();

    NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    String namedPipePath = null;
    try (NamedPipe namedPipe = namedPipeFactory.create()) {
      namedPipePath = namedPipe.getName();
      println(stringWriter, "Named pipe: " + namedPipePath);
      executorService.execute(readFromNamedPipeRunnable(namedPipePath, stringWriter));

      try (OutputStream outputStream = namedPipe.getOutputStream();
          BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
        println(stringWriter, "Starting write messages into a named pipe!");
        writeToNamedPipe(writer, "Hello pipe reader!");
        TimeUnit.SECONDS.sleep(1);
        writeToNamedPipe(writer, "Hello again!");
        TimeUnit.SECONDS.sleep(1);
        writeToNamedPipe(writer, "Bye!");
        writeToNamedPipe(writer, EOF, /* withPrefix */ false);
      } catch (IOException e) {
        println(stringWriter, "Can't write into a named pipe: " + namedPipePath, e);
      } finally {
        executorService.shutdown();
        if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      }
    } catch (Exception e) {
      println(stringWriter, "Can't create a named pipe.", e);
    }

    assertFalse("Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipePath)));

    String output = stringWriter.toString();
    LOG.info("Output: " + System.lineSeparator() + "---" + System.lineSeparator() + output);

    int messages = 0;
    try (Scanner scanner = new Scanner(output)) {
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        if (line.startsWith(READ_MESSAGE_PREFIX)) {
          messages++;

          line = line.substring(READ_MESSAGE_PREFIX.length());
          String receiveTimestampString = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
          line = line.substring(line.indexOf(WRITE_MESSAGE_PREFIX) + WRITE_MESSAGE_PREFIX.length());
          String sendTimestampString = line.substring(line.indexOf("[") + 1, line.indexOf("]"));

          Instant receivedInstant = Instant.parse(receiveTimestampString);
          Instant sentInstant = Instant.parse(sendTimestampString);

          assertFalse(
              "Receive timestamp: "
                  + receiveTimestampString
                  + "must be after or equals send timestamp: "
                  + sendTimestampString,
              receivedInstant.isBefore(sentInstant));
          assertFalse(
              "Receive timestamp: "
                  + receiveTimestampString
                  + "must be not more than 100ms different from the send timestamp: "
                  + sendTimestampString,
              receivedInstant.minus(Duration.ofMillis(100)).isAfter(sentInstant));
        }
      }
    }

    assertEquals("Expected that reader was able to read 3 messages over named pipe!", 3, messages);
  }

  private static Runnable readFromNamedPipeRunnable(String namedPipePath, Writer writer) {
    return new Runnable() {

      @Override
      public void run() {
        try {
          readFromNamedPath(namedPipePath);
        } catch (IOException e) {
          LOG.error(e);
        }
      }

      private void readFromNamedPath(String namedPipePath) throws IOException {
        println(writer, "Read named pipe path: " + namedPipePath);

        NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
        try (NamedPipe namedPipe = namedPipeFactory.connect(Paths.get(namedPipePath))) {
          try (InputStream inputStream = namedPipe.getInputStream();
              BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;
            while ((line = reader.readLine()) != null) {
              if (line.equals(EOF)) {
                println(writer, "Pipe is closed!");
                break;
              }
              handleReadLine(line);
            }
            println(writer, "Finishing reader thread!");
          } catch (IOException e) {
            println(writer, "Can't read from a named pipe: " + namedPipePath, e);
          }
        }
      }

      private void handleReadLine(String line) throws IOException {
        println(writer, READ_MESSAGE_PREFIX + " [" + Instant.now() + "]\t" + line);
      }
    };
  }

  private static void writeToNamedPipe(BufferedWriter writer, String message) throws IOException {
    writeToNamedPipe(writer, message, true);
  }

  private static void writeToNamedPipe(BufferedWriter writer, String message, boolean withPrefix)
      throws IOException {
    if (withPrefix) {
      writer.write(WRITE_MESSAGE_PREFIX + " [" + Instant.now() + "]\t");
    }
    writer.write(message);
    writer.newLine();
    writer.flush();
  }

  private static void println(Writer writer, String line) throws IOException {
    println(writer, line, null);
  }

  private static void println(Writer writer, String line, Exception e) throws IOException {
    writer.write(line);
    writer.write(System.lineSeparator());
    if (e != null) {
      writer.write("cause:" + Throwables.getStackTraceAsString(e));
      writer.write(System.lineSeparator());
    }
  }
}
