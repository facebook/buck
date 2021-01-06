/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Run an sh_test executable, and write its exit code, stdout, stderr to a file to be interpreted
 * later. Note that windows is unsupported at this time.
 */
public class RunShTestAndRecordResultStep extends RunTestAndRecordResultStep {

  // TODO: Break this into windows and non-windows
  public RunShTestAndRecordResultStep(
      ProjectFilesystem filesystem,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Optional<Long> testRuleTimeoutMs,
      BuildTarget buildTarget,
      Path pathToTestResultFile) {
    super(
        filesystem,
        command,
        env,
        testRuleTimeoutMs,
        buildTarget,
        pathToTestResultFile,
        command.get(0),
        "sh_test");
  }

  @Override
  protected TestResultSummary getTestSummary(ExecutionContext context)
      throws IOException, InterruptedException {
    if (context.getPlatform() == Platform.WINDOWS) {
      // Ignore sh_test on Windows.
      return new TestResultSummary(
          getShortName(),
          "sh_test",
          /* type */ ResultType.SUCCESS,
          /* duration*/ 0,
          /* message */ "sh_test ignored on Windows",
          /* stacktrace */ null,
          /* stdout */ null,
          /* stderr */ null);
    }
    return super.getTestSummary(context);
  }
}
