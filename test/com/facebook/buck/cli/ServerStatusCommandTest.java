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

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.testutil.TestConsole;
import com.google.common.base.Optional;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class ServerStatusCommandTest extends EasyMockSupport {

  private TestConsole console;
  private WebServer webServer;
  private CommandRunnerParams params;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    webServer = createStrictMock(WebServer.class);
    params = CommandRunnerParamsForTesting.builder()
        .setWebserver(Optional.of(webServer))
        .setConsole(console)
        .build();
  }

  @Test
  public void testWhenHttpserverRunning() throws IOException, InterruptedException {
    expect(webServer.getPort()).andStubReturn(Optional.of(9000));
    replayAll();

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.run(params);
    assertEquals("http.port=9000", console.getTextWrittenToStdOut().trim());
  }

  @Test
  public void testWhenHttpserverNotRunning() throws IOException, InterruptedException {
    expect(webServer.getPort()).andStubReturn(Optional.<Integer>absent());
    replayAll();

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.run(params);
    assertEquals("http.port=-1", console.getTextWrittenToStdOut().trim());
  }

  @Test
  public void testPrintJson() throws IOException, InterruptedException {
    expect(webServer.getPort()).andStubReturn(Optional.of(9000));
    replayAll();

    ServerStatusCommand command = new ServerStatusCommand();
    command.enableShowHttpserverPort();
    command.enablePrintJson();
    command.run(params);
    assertEquals("{\"http.port\":9000}", console.getTextWrittenToStdOut().trim());
  }
}
