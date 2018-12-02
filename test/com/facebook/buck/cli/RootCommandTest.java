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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import org.junit.Test;

public class RootCommandTest {

  @Test
  public void testPrintsProjectRootToStdout() throws Exception {
    TestConsole console = new TestConsole();
    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).build();

    RootCommand runCommand = new RootCommand();
    ExitCode exitCode = runCommand.run(commandRunnerParams);
    String testRoot =
        commandRunnerParams
            .getCell()
            .getFilesystem()
            .getRootPath()
            .normalize()
            .toAbsolutePath()
            .toString();

    assertThat(console.getTextWrittenToStdOut(), containsString(testRoot));
    assertThat(exitCode, equalTo(ExitCode.SUCCESS));
  }
}
