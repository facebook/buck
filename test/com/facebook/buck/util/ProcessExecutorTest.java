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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProcessExecutorTest {
  @Test
  public void testDontExpectStdout() throws IOException, InterruptedException {
    CapturingPrintStream stdOut = new CapturingPrintStream();
    CapturingPrintStream stdErr = new CapturingPrintStream();
    Ansi ansi = Ansi.forceTty();
    Console console = new Console(
        Verbosity.ALL, stdOut, stdErr, ansi);
    ProcessExecutor executor = new ProcessExecutor(console);
    ProcessExecutor.Result result = executor.execute(Runtime.getRuntime().exec("echo Hello"));
    assertEquals(ansi.asHighlightedFailureText("Hello\n"), result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }

  @Test
  public void testExpectStdout() throws IOException, InterruptedException {
    CapturingPrintStream stdOut = new CapturingPrintStream();
    CapturingPrintStream stdErr = new CapturingPrintStream();
    Ansi ansi = Ansi.forceTty();
    Console console = new Console(
        Verbosity.ALL, stdOut, stdErr, ansi);
    ProcessExecutor executor = new ProcessExecutor(console);
    ProcessExecutor.Result result = executor.execute(
        Runtime.getRuntime().exec("echo Hello"),
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent(),
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    assertEquals("Hello\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }

  @Test
  public void testProcessFailureDoesNotWriteEmptyString() throws IOException, InterruptedException {
    DirtyPrintStreamDecorator stdOut =
        new DirtyPrintStreamDecorator(new CapturingPrintStream());
    DirtyPrintStreamDecorator stdErr =
        new DirtyPrintStreamDecorator(new CapturingPrintStream());
    Ansi ansi = Ansi.forceTty();
    Console console = new Console(
        Verbosity.ALL, stdOut, stdErr, ansi);
    ProcessExecutor executor = new ProcessExecutor(console);
    executor.execute(Runtime.getRuntime().exec("false"));
    assertFalse(stdOut.isDirty());
    assertFalse(stdErr.isDirty());
  }

  @Test
  public void testProcessTimeoutHandlerIsInvoked() throws IOException, InterruptedException {
    Console console = new Console(
        Verbosity.ALL, new CapturingPrintStream(), new CapturingPrintStream(), Ansi.withoutTty());
    ProcessExecutor executor = new ProcessExecutor(console);

    final AtomicBoolean called = new AtomicBoolean(false);
    Function<Process, Void> handler = new Function<Process, Void>() {
      @Override
      public Void apply(Process input) {
        called.set(true);
        return null;
      }
    };

    String command = "sleep 50";
    if (Platform.detect() == Platform.WINDOWS) {
      command = "ping -n 50 0.0.0.0";
    }
    ProcessExecutor.Result result = executor.execute(
        Runtime.getRuntime().exec(command),
        /* options */ ImmutableSet.<ProcessExecutor.Option>builder().build(),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.of((long) 100),
        /* timeOutHandler */ Optional.of(handler));
    assertTrue(
        "process was reported as timed out",
        result.isTimedOut());
    assertTrue(
        "timeOutHandler was called when a timeout was hit",
        called.get());
  }

  @Test
  public void testProcessTimeoutHandlerThrowsException() throws IOException, InterruptedException {
    Console console = new Console(
        Verbosity.ALL, new CapturingPrintStream(), new CapturingPrintStream(), Ansi.withoutTty());
    ProcessExecutor executor = new ProcessExecutor(console);

    Function<Process, Void> handler = new Function<Process, Void>() {
      @Override
      public Void apply(Process input) {
        throw new RuntimeException("This shouldn't fail the test!");
      }
    };

    String command = "sleep 50";
    if (Platform.detect() == Platform.WINDOWS) {
      command = "ping -n 50 0.0.0.0";
    }
    ProcessExecutor.Result result = executor.execute(
        Runtime.getRuntime().exec(command),
        /* options */ ImmutableSet.<ProcessExecutor.Option>builder().build(),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.of((long) 100),
        /* timeOutHandler */ Optional.of(handler));
    assertTrue(
        "process was reported as timed out",
        result.isTimedOut());
  }
}
