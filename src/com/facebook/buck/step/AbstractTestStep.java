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

package com.facebook.buck.step;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Abstract implementation of {@link Step} that ... */
public abstract class AbstractTestStep implements Step {

  private final String name;
  private final ProjectFilesystem filesystem;
  private final Optional<Path> workingDirectory;
  private final ImmutableList<String> command;
  private final Optional<ImmutableMap<String, String>> env;
  private final Path exitCode;
  private final Path output;
  private final Optional<Long> testRuleTimeoutMs;

  public AbstractTestStep(
      String name,
      ProjectFilesystem filesystem,
      Optional<Path> workingDirectory,
      ImmutableList<String> command,
      Optional<ImmutableMap<String, String>> env,
      Path exitCode,
      Optional<Long> testRuleTimeoutMs,
      Path output) {
    this.name = name;
    this.filesystem = filesystem;
    this.workingDirectory = workingDirectory;
    this.command = command;
    this.env = env;
    this.exitCode = exitCode;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.output = output;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    // Build the process, redirecting output to the provided output file.  In general,
    // it's undesirable that both stdout and stderr are being redirected to the same
    // input stream.  However, due to the nature of OS pipe buffering, we can't really
    // maintain the natural interleaving of multiple output streams in a way that we
    // can correctly associate both stdout/stderr streams to the one correct test out
    // of the many that ran.  So, our best bet is to just combine them all into stdout,
    // so they get properly interleaved with the test start and end messages that we
    // use when we parse the test output.
    Map<String, String> environment = new HashMap<>(EnvVariablesProvider.getSystemEnv());
    if (env.isPresent()) {
      environment.putAll(env.get());
    }
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setDirectory(workingDirectory.map(filesystem::resolve))
            .setEnvironment(ImmutableMap.copyOf(environment))
            .setRedirectOutput(ProcessBuilder.Redirect.to(filesystem.resolve(output).toFile()))
            .setRedirectErrorStream(true)
            .build();

    ProcessExecutor.Result result;
    // Run the test process, saving the exit code.
    ProcessExecutor executor = context.getProcessExecutor();
    ImmutableSet<ProcessExecutor.Option> options =
        ImmutableSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    result =
        executor.launchAndExecute(
            params,
            options,
            /* stdin */ Optional.empty(),
            testRuleTimeoutMs,
            /* timeOutHandler */ Optional.empty());

    if (result.isTimedOut()) {
      throw new HumanReadableException(
          "Timed out after %d ms running test command %s", testRuleTimeoutMs.orElse(-1L), command);
    }

    // Since test binaries return a non-zero exit code when unittests fail, save the exit code
    // to a file rather than signalling a step failure.
    try (FileOutputStream fileOut = new FileOutputStream(filesystem.resolve(exitCode).toFile());
        ObjectOutputStream objectOut = new ObjectOutputStream(fileOut)) {
      objectOut.writeInt(result.getExitCode());
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return name;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return name;
  }

  public ImmutableList<String> getCommand() {
    return command;
  }

  @VisibleForTesting
  public Optional<ImmutableMap<String, String>> getEnv() {
    return env;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof AbstractTestStep)) {
      return false;
    }

    AbstractTestStep that = (AbstractTestStep) o;

    if (!command.equals(that.command)) {
      return false;
    }

    if (!exitCode.equals(that.exitCode)) {
      return false;
    }

    return output.equals(that.output);
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
}
