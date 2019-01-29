/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class CxxTestStepTest {

  @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private Path exitCode;
  private Path output;
  private ExecutionContext context;
  private FakeProjectFilesystem filesystem;

  private static int readExitCode(Path file) throws IOException {
    try (FileInputStream fileIn = new FileInputStream(file.toFile());
        ObjectInputStream objIn = new ObjectInputStream(fileIn)) {
      return objIn.readInt();
    }
  }

  private static void assertContents(Path file, String contents) throws IOException {
    assertEquals(contents, new String(Files.readAllBytes(file)));
  }

  @Before
  public void setUp() throws IOException {
    exitCode = tmpDir.newFile("exitCode").toPath();
    output = tmpDir.newFile("output").toPath();
    context = TestExecutionContext.newInstanceWithRealProcessExecutor();
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void success() throws IOException, InterruptedException {
    ImmutableList<String> trueCmd =
        Platform.detect() == Platform.WINDOWS
            ? ImmutableList.of("cmd", "/C", "(exit 0)")
            : ImmutableList.of("true");
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            trueCmd,
            ImmutableMap.of(),
            exitCode,
            output,
            /* testRuleTimeoutMs */ Optional.empty());
    step.execute(context);
    assertSame(0, readExitCode(exitCode));
    assertContents(output, "");
  }

  @Test
  public void failure() throws IOException, InterruptedException {
    ImmutableList<String> falseCmd =
        Platform.detect() == Platform.WINDOWS
            ? ImmutableList.of("cmd", "/C", "(exit 1)")
            : ImmutableList.of("false");
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            falseCmd,
            ImmutableMap.of(),
            exitCode,
            output,
            /* testRuleTimeoutMs */ Optional.empty());
    step.execute(context);
    assertSame(1, readExitCode(exitCode));
    assertContents(output, "");
  }

  @Test
  public void output() throws IOException, InterruptedException {
    String stdout = "hello world";
    ImmutableList<String> echoCmd =
        Platform.detect() == Platform.WINDOWS
            ? ImmutableList.of("powershell", "-Command", "echo", "'" + stdout + "'")
            : ImmutableList.of("echo", stdout);
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            echoCmd,
            ImmutableMap.of(),
            exitCode,
            output,
            /* testRuleTimeoutMs */ Optional.empty());
    step.execute(context);
    assertSame(0, readExitCode(exitCode));
    assertContents(output, stdout + System.lineSeparator());
  }

  @Test
  public void timeout() throws IOException, InterruptedException {
    ImmutableList<String> sleepCmd =
        Platform.detect() == Platform.WINDOWS
            ? ImmutableList.of("powershell", "-Command", "sleep 10")
            : ImmutableList.of("sleep", "10");
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            sleepCmd,
            ImmutableMap.of(),
            exitCode,
            output,
            /* testRuleTimeoutMs */ Optional.of(10L));
    expectedException.expectMessage("Timed out after 10 ms running test command");
    step.execute(context);
  }
}
