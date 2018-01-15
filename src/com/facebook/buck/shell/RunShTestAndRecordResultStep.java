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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public class RunShTestAndRecordResultStep implements Step {

  private final BuildTarget buildTarget;
  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> command;
  private final ImmutableMap<String, String> env;
  private final Path pathToTestResultFile;
  private final Optional<Long> testRuleTimeoutMs;
  private final String testCaseName;

  public RunShTestAndRecordResultStep(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Optional<Long> testRuleTimeoutMs,
      String testCaseName,
      Path pathToTestResultFile) {
    this.buildTarget = buildTarget;
    this.filesystem = filesystem;
    this.command = command;
    this.env = env;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.testCaseName = testCaseName;
    this.pathToTestResultFile = pathToTestResultFile;
  }

  @Override
  public String getShortName() {
    return command.get(0);
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return command.get(0);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    TestResultSummary summary;
    if (context.getPlatform() == Platform.WINDOWS) {
      // Ignore sh_test on Windows.
      summary =
          new TestResultSummary(
              getShortName(),
              "sh_test",
              /* type */ ResultType.SUCCESS,
              /* duration*/ 0,
              /* message */ "sh_test ignored on Windows",
              /* stacktrace */ null,
              /* stdout */ null,
              /* stderr */ null);
    } else {
      ShellStep test =
          new ShellStep(Optional.of(buildTarget), filesystem.getRootPath()) {
            boolean timedOut = false;

            @Override
            public String getShortName() {
              return command.get(0);
            }

            @Override
            protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
              return command;
            }

            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
              return ImmutableMap.<String, String>builder()
                  .put("NO_BUCKD", "1")
                  .putAll(env)
                  .build();
            }

            @Override
            protected boolean shouldPrintStderr(Verbosity verbosity) {
              // Do not stream this output because we want to capture it.
              return false;
            }

            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              StepExecutionResult executionResult = super.execute(context);
              if (timedOut) {
                throw new HumanReadableException(
                    "Timed out running test: "
                        + testCaseName
                        + ", with exitCode: "
                        + executionResult.getExitCode());
              }
              return executionResult;
            }

            @Override
            protected Optional<Consumer<Process>> getTimeoutHandler(
                final ExecutionContext context) {
              return Optional.of(process -> timedOut = true);
            }

            @Override
            protected Optional<Long> getTimeout() {
              return testRuleTimeoutMs;
            }

            @Override
            protected boolean shouldPrintStdout(Verbosity verbosity) {
              // Do not stream this output because we want to capture it.
              return false;
            }
          };
      StepExecutionResult executionResult = test.execute(context);

      // Write test result.
      boolean isSuccess = executionResult.isSuccess();
      summary =
          new TestResultSummary(
              getShortName(),
              "sh_test",
              /* type */ isSuccess ? ResultType.SUCCESS : ResultType.FAILURE,
              test.getDuration(),
              /* message */ null,
              /* stacktrace */ null,
              test.getStdout(),
              test.getStderr());
    }

    try (OutputStream outputStream = filesystem.newFileOutputStream(pathToTestResultFile)) {
      ObjectMappers.WRITER.writeValue(outputStream, summary);
    }

    // Even though the test may have failed, this command executed successfully, so its exit code
    // should be zero.
    return StepExecutionResults.SUCCESS;
  }
}
