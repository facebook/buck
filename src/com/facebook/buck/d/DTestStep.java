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

package com.facebook.buck.d;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs a D test command, remembering its exit code and streaming its output to
 * a given output file.
 */
public class DTestStep implements Step {

  private final ImmutableList<String> command;
  private final Path exitCode;
  private final Path output;

  public DTestStep(ImmutableList<String> command, Path exitCode, Path output) {
    this.command = command;
    this.exitCode = exitCode;
    this.output = output;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    // Build the process, redirecting output to the provided output file.  In general,
    // it's undesirable that both stdout and stderr are being redirected to the same
    // input stream.  However, due to the nature of OS pipe buffering, we can't really
    // maintain the natural interleaving of multiple output streams in a way that we
    // can correctly associate both stdout/stderr streams to the one correct test out
    // of the many that ran.  So, our best bet is to just combine them all into stdout,
    // so they get properly interleaved with the test start and end messages that we
    // use when we parse the test output.
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(command);
    builder.redirectOutput(filesystem.resolve(output).toFile());
    builder.redirectErrorStream(true);

    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      context.logError(e, "Error starting command %s", command);
      return 1;
    }

    // Run the test process, saving the exit code.
    ProcessExecutor executor = context.getProcessExecutor();
    ImmutableSet<ProcessExecutor.Option> options = ImmutableSet.of(
        ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result = executor.execute(
        process,
        options,
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent(),
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());

    // Since test binaries return a non-zero exit code when unittests fail, save the exit code
    // to a file rather than signalling a step failure.
    try (FileOutputStream stream = new FileOutputStream(filesystem.resolve(exitCode).toFile())) {
      stream.write((Integer.toString(result.getExitCode())).getBytes());
    } catch (IOException e) {
      context.logError(e, "Error saving exit code to %s", exitCode);
      return 1;
    }

    return 0;
  }

  @Override
  public String getShortName() {
    return "d test";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "d test";
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
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("command", command)
        .add("exitCode", exitCode)
        .add("output", output)
        .toString();
  }

}
