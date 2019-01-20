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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Runs buck with various commands and optional user configurations */
public class BuckRunner {

  private final List<String> fullCommand;
  private final List<String> argsInTempFile;
  private final Optional<Path> repositoryPath;
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
   * @param argsInTempFile Any arguments that should be written to a temporary file. This is useful
   *     for long lists of targets that may exceed argument counts on the CLI. No temporary file is
   *     created if this list is empty.
   * @param repositoryPath The path to run buck from. If not provided, run from the current dir
   * @param randomizeEnvironment If true, try to randomize the parsing environment for buck
   */
  public BuckRunner(
      String buckCommand,
      String buckSubcommand,
      List<String> extraBuckOptions,
      List<String> buckSubCommandOptions,
      List<String> argsInTempFile,
      Optional<Path> repositoryPath,
      boolean randomizeEnvironment) {
    this(
        Optional.empty(),
        buckCommand,
        buckSubcommand,
        extraBuckOptions,
        buckSubCommandOptions,
        argsInTempFile,
        repositoryPath,
        randomizeEnvironment);
  }

  /**
   * Runs the buck command with several arguments, and makes the output available to the caller. The
   * extra interpreter argument is used sometimes because windows does not let one execute scripts
   * directly; the interpreter has to be specified
   *
   * @param interpreter The interpreter to prefix the command with
   * @param buckCommand The path to the buck command
   * @param buckSubcommand The subcommand to run
   * @param extraBuckOptions Any arguments that need to come between the subcommand and the
   *     subcommand's arguments. e.g. -c cxx.cc=/bin/gcc
   * @param buckSubCommandOptions Any options that need to go to the subcommand, like target names
   * @param argsInTempFile Any arguments that should be written to a temporary file. This is useful
   *     for long lists of targets that may exceed argument counts on the CLI. No temporary file is
   *     created if this list is empty.
   * @param repositoryPath The path to run buck from. If not provided, run from the current dir
   * @param randomizeEnvironment If true, try to randomize the parsing environment for buck
   */
  @VisibleForTesting
  public BuckRunner(
      Optional<String> interpreter,
      String buckCommand,
      String buckSubcommand,
      List<String> extraBuckOptions,
      List<String> buckSubCommandOptions,
      List<String> argsInTempFile,
      Optional<Path> repositoryPath,
      boolean randomizeEnvironment) {
    this.argsInTempFile = argsInTempFile;
    this.repositoryPath = repositoryPath;
    this.randomizeEnvironment = randomizeEnvironment;
    this.fullCommand =
        ImmutableList.<String>builder()
            .addAll(interpreter.map(Collections::singletonList).orElse(Collections.emptyList()))
            .add(buckCommand, buckSubcommand)
            .addAll(extraBuckOptions)
            .addAll(buckSubCommandOptions)
            .build();
  }

  @Override
  public String toString() {
    return fullCommand.stream().collect(Collectors.joining(" "));
  }

  /**
   * Runs the buck command with several arguments, and makes the output available to the caller.
   * Buck is assumed to be on the path
   *
   * @param buckSubcommand The subcommand to run
   * @param extraBuckOptions Any arguments that need to come between the subcommand and the
   *     subcommand's arguments. e.g. -c cxx.cc=/bin/gcc
   * @param buckSubCommandOptions Any options that need to go to the subcommand, like target names
   * @param argsInTempFile Any arguments that should be written to a temporary file. This is useful
   *     for long lists of targets that may exceed argument counts on the CLI. No temporary file is
   *     created if this list is empty.
   * @param repositoryPath The path to run buck from. If not provided, run from the current dir
   */
  public BuckRunner(
      String buckSubcommand,
      List<String> extraBuckOptions,
      List<String> buckSubCommandOptions,
      List<String> argsInTempFile,
      Optional<Path> repositoryPath) {
    this(
        "buck",
        buckSubcommand,
        extraBuckOptions,
        buckSubCommandOptions,
        argsInTempFile,
        repositoryPath,
        true);
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
    Optional<Path> tempPath = Optional.empty();
    try {
      if (!argsInTempFile.isEmpty()) {
        File tempFile = File.createTempFile("consistency-checker", "");
        tempPath = Optional.of(tempFile.toPath());
        try (BufferedWriter writer = Files.newBufferedWriter(tempPath.get())) {
          for (String arg : argsInTempFile) {
            writer.write(arg);
            writer.newLine();
          }
        }
      }
      return runProcess(outputStream, tempPath);
    } finally {
      if (tempPath.isPresent()) {
        Files.deleteIfExists(tempPath.get());
      }
    }
  }

  private int runProcess(OutputStream outputStream, Optional<Path> tempPath)
      throws IOException, InterruptedException {
    List<String> command = fullCommand;
    if (tempPath.isPresent()) {
      command =
          ImmutableList.<String>builder().addAll(fullCommand).add("@" + tempPath.get()).build();
    }
    ProcessBuilder builder = new ProcessBuilder(command).redirectError(Redirect.INHERIT);

    if (repositoryPath.isPresent()) {
      builder.directory(repositoryPath.get().toFile());
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
