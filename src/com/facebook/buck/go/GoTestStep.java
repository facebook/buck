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

package com.facebook.buck.go;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Path;

/**
 * Run a go test command and stream the output to a file.
 *
 * TODO(mikekap): Dedup this with CxxTestStep.
 */
public class GoTestStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path workingDirectory;
  private final ImmutableList<String> command;
  private final ImmutableMap<String, String> env;
  private final Path exitCode;
  private final Path output;
  private final Optional<Long> testRuleTimeoutMs;

  public GoTestStep(
      ProjectFilesystem filesystem,
      Path workingDirectory,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Path exitCode,
      Optional<Long> testRuleTimeoutMs,
      Path output) {
    this.filesystem = filesystem;
    this.workingDirectory = workingDirectory;
    this.command = command;
    this.env = env;
    this.exitCode = exitCode;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.output = output;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    // Build the process, redirecting output to the provided output file.  In general,
    // it's undesirable that both stdout and stderr are being redirected to the same
    // input stream.  However, due to the nature of OS pipe buffering, we can't really
    // maintain the natural interleaving of multiple output streams in a way that we
    // can correctly associate both stdout/stderr streams to the one correct test out
    // of the many that ran.  So, our best bet is to just combine them all into stdout,
    // so they get properly interleaved with the test start and end messages that we
    // use when we parse the test output.
    ProcessBuilder builder = new ProcessBuilder();
    builder.directory(filesystem.resolve(workingDirectory).toFile());
    builder.command(command);
    builder.environment().putAll(env);
    builder.redirectOutput(filesystem.resolve(output).toFile());
    builder.redirectErrorStream(true);

    Process process;
    try {
      process = BgProcessKiller.startProcess(builder);
    } catch (IOException e) {
      context.logError(e, "Error starting command %s", command);
      return StepExecutionResult.ERROR;
    }

    // Run the test process, saving the exit code.
    ProcessExecutor executor = context.getProcessExecutor();
    ImmutableSet<ProcessExecutor.Option> options = ImmutableSet.of(
        ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result = executor.execute(
        process,
        options,
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ testRuleTimeoutMs,
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());

    if (result.isTimedOut()) {
      throw new HumanReadableException(
          "Timed out after %d ms running test command %s",
          testRuleTimeoutMs.or(-1L),
          command);
    }

    // Since test binaries return a non-zero exit code when unittests fail, save the exit code
    // to a file rather than signalling a step failure.
    try (FileOutputStream fileOut = new FileOutputStream(filesystem.resolve(exitCode).toFile());
         ObjectOutputStream objectOut = new ObjectOutputStream(fileOut)) {
      objectOut.writeInt(result.getExitCode());
    } catch (IOException e) {
      context.logError(e, "Error saving exit code to %s", exitCode);
      return StepExecutionResult.ERROR;
    }

    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "go test";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "go test";
  }

  public ImmutableList<String> getCommand() {
    return command;
  }

  public Path getExitCode() {
    return exitCode;
  }

  public Path getOutput() {
    return output;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof GoTestStep)) {
      return false;
    }

    GoTestStep that = (GoTestStep) o;

    if (!command.equals(that.command)) {
      return false;
    }

    if (!exitCode.equals(that.exitCode)) {
      return false;
    }

    if (!output.equals(that.output)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(command, exitCode, output);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("command", command)
        .add("env", env)
        .add("exitCode", exitCode)
        .add("output", output)
        .toString();
  }

  public ImmutableMap<String, String> getEnv() {
    return env;
  }

}
