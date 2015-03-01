/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.testutil.TestConsole;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.IOException;

public class RunCommandTest extends EasyMockSupport {

  @Test
  public void testRunCommandWithNoArguments()
      throws IOException, InterruptedException {
    TestConsole console = new TestConsole();
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .setConsole(console)
        .build();

    RunCommand runCommand = new RunCommand(commandRunnerParams);
    BuckConfig buckConfig = new FakeBuckConfig();

    RunCommandOptions options = new RunCommandOptions(buckConfig);
    int exitCode = runCommand.runCommandWithOptionsInternal(options);
    assertEquals("buck run <target> <arg1> <arg2>...\n", console.getTextWrittenToStdOut());
    assertEquals("BUILD FAILED: No target given to run\n", console.getTextWrittenToStdErr());
    assertEquals(1, exitCode);
  }

  @Test
  public void testRunCommandWithNonExistentTarget()
      throws IOException, InterruptedException {
    TestConsole console = new TestConsole();
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .setConsole(console)
        .build();

    RunCommand runCommand = new RunCommand(commandRunnerParams);
    BuckConfig buckConfig = new FakeBuckConfig();

    RunCommandOptions options = new RunCommandOptions(buckConfig);
    options.setArguments(ImmutableList.of("//does/not/exist"));
    int exitCode = runCommand.runCommandWithOptionsInternal(options);
    assertEquals("", console.getTextWrittenToStdOut());
    String stderrText = console.getTextWrittenToStdErr();
    assertEquals(
        "BUILD FAILED: No build file at does/not/exist/BUCK when resolving target " +
        "//does/not/exist:exist.\n",
        stderrText);
    assertEquals(1, exitCode);
  }

}
