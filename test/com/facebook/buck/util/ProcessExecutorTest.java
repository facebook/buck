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

import com.google.common.base.Optional;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;

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
        Optional.<String>absent());
    assertEquals("Hello\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }
}
