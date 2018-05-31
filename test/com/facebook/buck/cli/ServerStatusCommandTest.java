/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.Before;
import org.junit.Test;

public class ServerStatusCommandTest {

  private TestConsole console;
  private CommandRunnerParams params;
  private OptionalInt webServerPort;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    WebServer webServer =
        new WebServer(0, new FakeProjectFilesystem()) {
          @Override
          public OptionalInt getPort() {
            return webServerPort;
          }
        };
    params =
        CommandRunnerParamsForTesting.builder()
            .setWebserver(Optional.of(webServer))
            .setConsole(console)
            .build();
  }

  @Test
  public void testWhenHttpserverRunning() throws IOException, InterruptedException {
    webServerPort = OptionalInt.of(9000);

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.run(params);
    assertEquals("http.port=9000", console.getTextWrittenToStdOut().trim());
  }

  @Test
  public void testWhenHttpserverNotRunning() throws IOException, InterruptedException {
    webServerPort = OptionalInt.empty();

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.run(params);
    assertEquals("http.port=-1", console.getTextWrittenToStdOut().trim());
  }

  @Test
  public void testPrintJson() throws IOException, InterruptedException {
    webServerPort = OptionalInt.of(9000);

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.enablePrintJson();
    command.run(params);
    assertEquals("{\"http.port\":9000}", console.getTextWrittenToStdOut().trim());
  }
}
