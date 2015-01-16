/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.martiansoftware.nailgun.NGContext;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;

public class MainTest {

  private PrintStream stdOut;

  @Before
  public void setUp() {
    this.stdOut = EasyMock.createMock(PrintStream.class);
    EasyMock.replay(this.stdOut);
  }

  @After
  public void tearDown() {
    EasyMock.verify(this.stdOut);
    this.stdOut = null;
  }

  @Test
  public void testUsage() {
    CapturingPrintStream stdErr = new CapturingPrintStream();

    Main main = new Main(stdOut, stdErr);
    int exitCode = main.usage();
    assertEquals("When usage information is displayed, the exit code is 1.", 1, exitCode);
    assertEquals(getUsageString(), stdErr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testBuckNoArgs() throws IOException, InterruptedException {
    CapturingPrintStream stdErr = new CapturingPrintStream();

    Main main = new Main(stdOut, stdErr);
    int exitCode = main.runMainWithExitCode(
        new BuildId(),
        Paths.get("."),
        Optional.<NGContext>absent(),
        ImmutableMap.<String, String>of());
    assertEquals(1, exitCode);
    assertEquals(
        "When the user does not specify any arguments, the usage information should be displayed",
        getUsageString(),
        stdErr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testBuckHelp() throws IOException, InterruptedException {
    CapturingPrintStream stdErr = new CapturingPrintStream();

    Main main = new Main(stdOut, stdErr);
    int exitCode = main.runMainWithExitCode(
        new BuildId(),
        Paths.get("."),
        Optional.<NGContext>absent(),
        ImmutableMap.<String, String>of(),
        "--help");
    assertEquals(1, exitCode);
    assertEquals("Users instinctively try running `buck --help`, so it should print usage info.",
        getUsageString(),
        stdErr.getContentsAsString(Charsets.US_ASCII));
  }

  private String getUsageString() {
    return Joiner.on('\n').join(
        "buck build tool",
        "usage:",
        "  buck [options]",
        "  buck command --help",
        "  buck command [command-options]",
        "available commands:",
        "  audit       lists the inputs for the specified target",
        "  build       builds the specified target",
        "  cache       makes calls to the artifact cache",
        "  clean       deletes any generated files",
        "  fetch       downloads remote resources to your local machine",
        "  install     builds and installs an APK",
        "  project     generates project configuration files for an IDE",
        "  quickstart  generates a default project directory",
        "  run         runs a target as a command",
        "  targets     prints the list of buildable targets",
        "  test        builds and runs the tests for the specified target",
        "  uninstall   uninstalls an APK",
        "options:",
        " --help         : Shows this screen and exits.",
        " --version (-V) : Show version number.",
        "");
  }
}
