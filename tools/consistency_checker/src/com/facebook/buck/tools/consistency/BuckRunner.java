/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** Runs buck with various commands and optional user configurations */
public class BuckRunner {

  private final List<String> fullCommand;
  private final Optional<String> repositoryPath;
  private final boolean randomizeEnvironment;
  private Logger LOG = Logger.getLogger(BuckRunner.class.toString());

  /**
   * Runs the buck command with several arguments, and makes the output available to the caller
   *
   * @param buckCommand The path to the buck command
   * @param buckSubcommand The subcommand to run
   * @param extraBuckOptions Any arguments that need to come between the subcommand and the
   *     subcommand's arguments. e.g. -c cxx.cc=/bin/gcc
   * @param buckSubCommandOptions Any options that need to go to the subcommand, like target names
   * @param repositoryPath The path to run buck from. If not provided, run from the current dir
   * @param randomizeEnvironment If true, try to randomize the parsing environment for buck
   */
  public BuckRunner(
      String buckCommand,
      String buckSubcommand,
      List<String> extraBuckOptions,
      List<String> buckSubCommandOptions,
      Optional<String> repositoryPath,
      boolean randomizeEnvironment) {
    this.repositoryPath = repositoryPath;
    this.randomizeEnvironment = randomizeEnvironment;
    this.fullCommand =
        ImmutableList.<String>builder()
            .add(buckCommand, buckSubcommand)
            .addAll(extraBuckOptions)
            .addAll(buckSubCommandOptions)
            .build();
  }

  /**
   * Runs the buck command with several arguments, and makes the output available to the caller.
   * Buck is assumed to be on the path
   *
   * @param buckSubcommand The subcommand to run
   * @param extraBuckOptions Any arguments that need to come between the subcommand and the
   *     subcommand's arguments. e.g. -c cxx.cc=/bin/gcc
   * @param buckSubCommandOptions Any options that need to go to the subcommand, like target names
   * @param repositoryPath The path to run buck from. If not provided, run from the current dir
   */
  public BuckRunner(
      String buckSubcommand,
      List<String> extraBuckOptions,
      List<String> buckSubCommandOptions,
      Optional<String> repositoryPath) {
    this("buck", buckSubcommand, extraBuckOptions, buckSubCommandOptions, repositoryPath, true);
  }

  /**
   * Run the previously specified command. This blocks until buck is done running, and writes all
   * log output to {@code outputStream} in a background thread.
   *
   * @param outputStream The stream to write stdout to
   * @return The exit code of buck
   * @throws IOException Thrown if an IO issue comes up while starting the subprocess
   * @throws InterruptedException Possibly thrown while waiting for the process to complete
   */
  public int run(OutputStream outputStream) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(fullCommand).redirectError(Redirect.INHERIT);
    if (repositoryPath.isPresent()) {
      builder.directory(new File(repositoryPath.get()));
    }
    builder.environment().put("NO_BUCKD", "1");
    if (randomizeEnvironment) {
      // Randomize python
      builder.environment().put("PYTHONHASHSEED", "random");
    }

    Process buckProcess = builder.start();

    // Copy from stdout to the outputStream
    // Do this in a background thread because the output
    // buffer from a buck command can get quite large and
    // we don't want to block unnecessarily.
    Thread streamingThread =
        new Thread(
            () -> {
              InputStream stream = buckProcess.getInputStream();
              try {
                ByteStreams.copy(stream, outputStream);
              } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Could not write to output stream: %s", e.getMessage()), e);
              }
            });
    streamingThread.start();

    try {
      int returnCode = buckProcess.waitFor();
      streamingThread.join();
      return returnCode;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw e;
      }
    }
  }
}
