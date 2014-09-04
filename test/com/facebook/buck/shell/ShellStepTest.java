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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.logging.Level;

public class ShellStepTest extends EasyMockSupport {

  private ExecutionContext context;
  private TestConsole console;
  private Process process;

  private static final ImmutableList<String> ARGS = ImmutableList.of("bash", "-c", "echo $V1 $V2");

  private static final ImmutableMap<String, String> ENV = ImmutableMap.of(
      "V1", "two words",
      "V2", "$foo'bar'");

  private static final File PATH = new File("/tmp/a b");
  private static final String ERROR_MSG = "some syntax error\ncompilation failed\n";
  private static final String OUTPUT_MSG = "processing data...\n";
  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_SUCCESS = 0;

  @Before
  public void setUp() {
    context = createMock(ExecutionContext.class);
    replayAll();
  }

  @After
  public void tearDown() {
    verifyAll();
  }

  private void prepareContextForOutput(Verbosity verbosity) {
    resetAll();

    console = new TestConsole();
    console.setVerbosity(verbosity);
    ProcessExecutor processExecutor = new ProcessExecutor(console);

    expect(context.getStdErr()).andStubReturn(console.getStdErr());
    expect(context.getVerbosity()).andStubReturn(verbosity);
    expect(context.getProcessExecutor()).andStubReturn(processExecutor);

    context.postEvent(anyObject(BuckEvent.class));
    expectLastCall().andStubAnswer(
        new IAnswer<Void>() {
          @Override
          public Void answer() throws Throwable {
            ConsoleEvent event = (ConsoleEvent) getCurrentArguments()[0];
            if (event.getLevel().equals(Level.WARNING)) {
              console.getStdErr().write(event.getMessage().getBytes(Charsets.US_ASCII));
            }
            return null;
          }
        });

    replayAll();
  }

  private void prepareContextAndProcessForInput(
      Verbosity verbosity,
      final Optional<OutputStream> stdin) throws Exception {

    resetAll();

    console = new TestConsole();
    console.setVerbosity(verbosity);
    ProcessExecutor processExecutor = new ProcessExecutor(console);
    expect(context.getVerbosity()).andStubReturn(verbosity);
    expect(context.getProcessExecutor()).andStubReturn(processExecutor);

    // Setup a mocked process object.
    process = createMock(Process.class);

    // If a stdin stream was specified, then make the mocked process return it.
    if (stdin.isPresent()) {
      expect(process.getOutputStream()).andReturn(stdin.get());
    }

    // Set defaults for the output and exit values.
    expect(process.getInputStream()).andStubReturn(new ByteArrayInputStream(new byte[0]));
    expect(process.getErrorStream()).andStubReturn(new ByteArrayInputStream(new byte[0]));
    process.destroy();
    expect(process.waitFor()).andStubReturn(0);
    expect(process.exitValue()).andStubReturn(0);

    replayAll();
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

  private static ShellStep createCommand(
      ImmutableMap<String, String> env,
      ImmutableList<String> cmd,
      File workingDirectory) {
    return createCommand(
        env,
        cmd,
        workingDirectory,
        /* shouldPrintStdErr */ false,
        /* shouldRecordStdOut */ false,
        /* stdin */ Optional.<String>absent());
  }

  private static ShellStep createCommand(boolean shouldPrintStdErr, boolean shouldPrintStdOut) {
    return createCommand(
        ENV,
        ARGS,
        null,
        shouldPrintStdErr,
        shouldPrintStdOut,
        /* stdin */ Optional.<String>absent());
  }

  private static ShellStep createCommand(
      final ImmutableMap<String, String> env,
      final ImmutableList<String> cmd,
      File workingDirectory,
      final boolean shouldPrintStdErr,
      final boolean shouldPrintStdOut,
      final Optional<String> stdin) {
    return new ShellStep(workingDirectory) {
      @Override
      public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
        return env;
      }
      @Override
      public String getShortName() {
         return cmd.get(0);
      }
      @Override
      protected Optional<String> getStdin() {
        return stdin;
      }
      @Override
      protected ImmutableList<String> getShellCommandInternal(
          ExecutionContext context) {
        return cmd;
      }
      @Override
      protected boolean shouldPrintStderr(Verbosity verbosity) {
        return shouldPrintStdErr;
      }
      @Override
      protected boolean shouldPrintStdout(Verbosity verbosity) {
        return shouldPrintStdOut;
      }
    };
  }

  @Test
  public void testDescriptionWithEnvironment() {
    ShellStep command = createCommand(ENV, ARGS, null);
    assertEquals("V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2'",
        command.getDescription(context));
  }

  @Test
  public void testDescriptionWithEnvironmentAndPath() {
    ShellStep command = createCommand(ENV, ARGS, PATH);
    assertEquals(
        String.format("(cd '%s' && V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2')",
            PATH.getPath()),
        command.getDescription(context));
  }

  @Test
  public void testDescriptionWithPath() {
    ShellStep command = createCommand(ImmutableMap.<String, String>of(), ARGS, PATH);
    assertEquals(String.format("(cd '%s' && bash -c 'echo $V1 $V2')", PATH.getPath()),
        command.getDescription(context));
  }

  @Test
  public void testDescription() {
    ShellStep command = createCommand(ImmutableMap.<String, String>of(), ARGS, null);
    assertEquals("bash -c 'echo $V1 $V2'", command.getDescription(context));
  }

  @Test
  public void testStdErrPrintedOnErrorIfNotSilentEvenIfNotShouldPrintStdErr() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.STANDARD_INFORMATION);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrNotPrintedOnErrorIfSilentAndNotShouldPrintStdErr() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrPrintedOnErrorIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ true, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrNotPrintedOnSuccessIfNotShouldPrintStdErr() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.STANDARD_INFORMATION);
    command.interactWithProcess(context, process);
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrPrintedOnSuccessIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ true, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.SILENT);
    command.interactWithProcess(context, process);
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdOutNotPrintedIfNotShouldRecordStdoutEvenIfVerbose() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    Process process = createProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    prepareContextForOutput(Verbosity.ALL);
    command.interactWithProcess(context, process);
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void processEnvironmentIsUnionOfContextAndStepEnvironments() {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setEnvironment(ImmutableMap.of("CONTEXT_ENVIRONMENT_VARIABLE", "CONTEXT_VALUE"))
        .build();
    Map<String, String> subProcessEnvironment = Maps.newHashMap();
    subProcessEnvironment.put("PROCESS_ENVIRONMENT_VARIABLE", "PROCESS_VALUE");
    command.setProcessEnvironment(context, subProcessEnvironment);
    assertEquals("Sub-process environment should be union of client and step environments.",
        subProcessEnvironment,
        ImmutableMap.builder().putAll(context.getEnvironment()).putAll(ENV).build());
  }

  @Test
  public void testStdinGetsToProcessWhenPresent() throws Exception {
    final Optional<String> stdin = Optional.of("hello world!");
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    ShellStep command = createCommand(
        ImmutableMap.<String, String>of(),
        ImmutableList.of("cat", "-"),
        null,
        /*shouldPrintStdErr*/ true,
        /*shouldPrintStdOut*/ true,
        stdin);
    prepareContextAndProcessForInput(Verbosity.ALL, Optional.<OutputStream>of(output));
    command.interactWithProcess(context, process);
  }

  @Test
  public void testStdinDoesNotGetToProcessWhenAbsent() throws Exception {
    final Optional<String> stdin = Optional.absent();
    ShellStep command = createCommand(
        ImmutableMap.<String, String>of(),
        ImmutableList.of("cat", "-"),
        null,
        /*shouldPrintStdErr*/ true,
        /*shouldPrintStdOut*/ true,
        stdin);
    prepareContextAndProcessForInput(Verbosity.ALL, Optional.<OutputStream>absent());
    command.interactWithProcess(context, process);
  }

}
