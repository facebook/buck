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

package com.facebook.buck.shell;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public class ShellCommandTest {

  private ExecutionContext context;
  private CapturingPrintStream stdout;
  private CapturingPrintStream stderr;

  private static final ImmutableList<String> ARGS = ImmutableList.of("bash", "-c", "echo $V1 $V2");

  private static final ImmutableMap<String, String> ENV = ImmutableMap.of(
      "V1", "two words",
      "V2", "$foo'bar'"
  );

  private static final File PATH = new File("/tmp/a b");
  private static final String ERROR_MSG = "some syntax error\ncompilation failed\n";
  private static final String OUTPUT_MSG = "processing data...\n";
  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_SUCCESS = 0;

  @Before
  public void setUp() {
    context = createMock(ExecutionContext.class);
    stdout = new CapturingPrintStream();
    stderr = new CapturingPrintStream();
    replay(context);
  }

  @After
  public void tearDown() {
    verify(context);
  }

  private void prepareContextForOutput(Verbosity verbosity) {
    EasyMock.reset(context);
    expect(context.getStdErr()).andReturn(stderr).anyTimes();
    expect(context.getStdOut()).andReturn(stdout).anyTimes();
    expect(context.getAnsi()).andReturn(new Ansi(false)).anyTimes();
    expect(context.getVerbosity()).andReturn(verbosity).anyTimes();
    replay(context);
  }

  private static Process createProcess(
      final int exitValue,
      final String stdout,
      final String stderr) {
    return new Process() {

      @Override
      public OutputStream getOutputStream() {
        return null;
      }

      @Override
      public InputStream getInputStream() {
        return new ByteArrayInputStream(stdout.getBytes(Charsets.US_ASCII));
      }

      @Override
      public InputStream getErrorStream() {
        return new ByteArrayInputStream(stderr.getBytes(Charsets.US_ASCII));
      }

      @Override
      public int waitFor() {
        return exitValue;
      }

      @Override
      public int exitValue() {
        return exitValue;
      }

      @Override
      public void destroy() {
      }

    };
  }

  private static ShellCommand createCommand(
      ImmutableMap<String, String> env,
      ImmutableList<String> cmd,
      File workingDirectory) {
    return createCommand(
        env,
        cmd,
        workingDirectory,
        /* shouldPrintStdErr */ false,
        /* shouldRecordStdOut */ false);
  }

  private static ShellCommand createCommand(boolean shouldPrintStdErr, boolean shouldRecordStdOut) {
    return createCommand(ENV, ARGS, null, shouldPrintStdErr, shouldRecordStdOut);
  }

  private static ShellCommand createCommand(
      final ImmutableMap<String, String> env,
      final ImmutableList<String> cmd,
      File workingDirectory,
      final boolean shouldPrintStdErr,
      final boolean shouldRecordStdOut) {
    return new ShellCommand(null, workingDirectory) {
      @Override
      public ImmutableMap<String, String> getEnvironmentVariables() {
        return env;
      }
      @Override
      public String getShortName(ExecutionContext context) {
         return cmd.get(0);
      }
      @Override
      protected ImmutableList<String> getShellCommandInternal(
          ExecutionContext context) {
        return cmd;
      }
      @Override
      protected boolean shouldPrintStdErr(ExecutionContext context) {
        return shouldPrintStdErr;
      }
      @Override
      protected boolean shouldRecordStdout() {
        return shouldRecordStdOut;
      }
    };
  }

  @Test
  public void testDescriptionWithEnvironment() {
    ShellCommand command = createCommand(ENV, ARGS, null);
    assertEquals("V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2'",
        command.getDescription(context));
  }

  @Test
  public void testDescriptionWithEnvironmentAndPath() {
    ShellCommand command = createCommand(ENV, ARGS, PATH);
    assertEquals("(cd '/tmp/a b' && V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2')",
        command.getDescription(context));
  }

  @Test
  public void testDescriptionWithPath() {
    ShellCommand command = createCommand(ImmutableMap.<String,String>of(), ARGS, PATH);
    assertEquals("(cd '/tmp/a b' && bash -c 'echo $V1 $V2')",
        command.getDescription(context));
  }

  @Test
  public void testDescription() {
    ShellCommand command = createCommand(ImmutableMap.<String,String>of(), ARGS, null);
    assertEquals("bash -c 'echo $V1 $V2'", command.getDescription(context));
  }

  @Test
  public void testStdErrPrintedOnErrorIfNotSilentEvenIfNotShouldPrintStdErr() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ false, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.STANDARD_INFORMATION);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, stderr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testStdErrNotPrintedOnErrorIfSilentAndNotShouldPrintStdErr() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ false, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals("", stderr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testStdErrPrintedOnErrorIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ true, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, stderr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testStdErrNotPrintedOnSuccessIfNotShouldPrintStdErr() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ false, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.STANDARD_INFORMATION);
    command.interactWithProcess(context, process);
    assertEquals("", stderr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testStdErrPrintedOnSuccessIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ true, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, stderr.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testOuputRecordedButNotPrintedIfShouldRecordStdoutEvenIfVerbose() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ false, /*shouldRecordStdout*/ true);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.ALL);
    command.interactWithProcess(context, process);
    assertEquals(OUTPUT_MSG, command.getStdOut());
  }

  @Test
  public void testStdOutNotPrintedIfNotShouldRecordStdoutEvenIfVerbose() throws Exception {
    ShellCommand command = createCommand(/*shouldPrintStdErr*/ false, /*shouldRecordStdout*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.ALL);
    command.interactWithProcess(context, process);
    assertEquals("", stdout.getContentsAsString(Charsets.US_ASCII));
  }

}
