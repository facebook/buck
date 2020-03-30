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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Run a test executable, and write its exit code, stdout, stderr to a file to be interpreted later
 */
public class RunTestAndRecordResultStep implements Step {
  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> command;
  private final ImmutableMap<String, String> env;
  private final Path pathToTestResultFile;
  private final String shortName;
  private final String testName;
  private final Optional<Long> testRuleTimeoutMs;
  private final String mainTestCaseName;

  public RunTestAndRecordResultStep(
      ProjectFilesystem filesystem,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Optional<Long> testRuleTimeoutMs,
      BuildTarget buildTarget,
      Path pathToTestResultFile,
      String shortName,
      String testName) {
    this.filesystem = filesystem;
    this.command = command;
    this.env = env;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.mainTestCaseName = buildTarget.getFullyQualifiedName();
    this.pathToTestResultFile = pathToTestResultFile;
    this.shortName = shortName;
    this.testName = testName;
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return shortName;
  }

  /**
   * Run whatever binaries are necessary, and return a {@link TestResultSummary} explaining the
   * outcome
   */
  protected TestResultSummary getTestSummary(ExecutionContext context)
      throws IOException, InterruptedException {

    ShellStep test =
        new ShellStep(filesystem.getRootPath()) {
          boolean timedOut = false;

          @Override
          public String getShortName() {
            return shortName;
          }

          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return command;
          }

          @Override
          public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
            return ImmutableMap.<String, String>builder().put("NO_BUCKD", "1").putAll(env).build();
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
                      + mainTestCaseName
                      + ", with exitCode: "
                      + executionResult.getExitCode());
            }
            return executionResult;
          }

          @Override
          protected Optional<Consumer<Process>> getTimeoutHandler(ExecutionContext context) {
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
    return new TestResultSummary(
        getShortName(),
        testName,
        /* type */ isSuccess ? ResultType.SUCCESS : ResultType.FAILURE,
        test.getDuration(),
        /* message */ null,
        /* stacktrace */ null,
        test.getStdout(),
        test.getStderr());
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    TestResultSummary summary = getTestSummary(context);

    try (OutputStream outputStream = filesystem.newFileOutputStream(pathToTestResultFile)) {
      ObjectMappers.WRITER.writeValue(outputStream, summary);
    }

    // Even though the test may have failed, this command executed successfully, so its exit code
    // should be zero.
    return StepExecutionResults.SUCCESS;
  }
}
