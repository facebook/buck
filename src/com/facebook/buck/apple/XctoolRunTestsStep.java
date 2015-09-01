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

package com.facebook.buck.apple;

import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Runs {@code xctool} on one or more logic tests and/or application
 * tests (each paired with a host application).
 *
 * The output is written in streaming JSON format to stdout and is
 * parsed by {@link XctoolOutputParsing}.
 */
public class XctoolRunTestsStep implements Step {

  private final Path workingDirectory;

  public interface StdoutReadingCallback {
    void readStdout(InputStream stdout) throws IOException;
  }

  private static final Logger LOG = Logger.get(XctoolRunTestsStep.class);

  private final ImmutableList<String> command;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;

  public XctoolRunTestsStep(
      Path workingDirectory,
      Path xctoolPath,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback) {
    Preconditions.checkArgument(
        !(logicTestBundlePaths.isEmpty() &&
          appTestBundleToHostAppPaths.isEmpty()),
        "Either logic tests (%s) or app tests (%s) must be present",
        logicTestBundlePaths,
        appTestBundleToHostAppPaths);

    this.workingDirectory = workingDirectory;

    // Each test bundle must have one of these extensions. (xctool
    // depends on them to choose which test runner to use.)
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(logicTestBundlePaths),
        "Extension of all logic tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        logicTestBundlePaths);
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(appTestBundleToHostAppPaths.keySet()),
        "Extension of all app tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        appTestBundleToHostAppPaths.keySet());

    this.command = createCommandArgs(
        xctoolPath,
        sdkName,
        destinationSpecifier,
        logicTestBundlePaths,
        appTestBundleToHostAppPaths);
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
  }

  @Override
  public String getShortName() {
    return "xctool-run-tests";
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProcessExecutorParams processExecutorParams = ProcessExecutorParams.builder()
        .setCommand(command)
        .setDirectory(workingDirectory.toAbsolutePath().toFile())
        .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
        .build();

    try {
      LOG.debug("Running command: %s", processExecutorParams);

      // Start the process.
      ProcessExecutor.LaunchedProcess launchedProcess =
          context.getProcessExecutor().launchProcess(processExecutorParams);

      int exitCode;
      try (OutputStream outputStream = context.getProjectFilesystem().newFileOutputStream(
               outputPath);
           TeeInputStream stdoutWrapperStream = new TeeInputStream(
               launchedProcess.getInputStream(), outputStream)) {
        if (stdoutReadingCallback.isPresent()) {
          // The caller is responsible for reading all the data, which TeeInputStream will
          // copy to outputStream.
          stdoutReadingCallback.get().readStdout(stdoutWrapperStream);
        } else {
          // Nobody's going to read from stdoutWrapperStream, so close it and copy
          // the process's stdout to outputPath directly.
          stdoutWrapperStream.close();
          ByteStreams.copy(launchedProcess.getInputStream(), outputStream);
        }
        exitCode = waitForProcessAndGetExitCode(context.getProcessExecutor(), launchedProcess);
        LOG.debug("Finished running command, exit code %d", exitCode);
      } finally {
        context.getProcessExecutor().destroyLaunchedProcess(launchedProcess);
        context.getProcessExecutor().waitForLaunchedProcess(launchedProcess);
      }

      if (exitCode != 0) {
        LOG.warn("%s exited with error %d", command, exitCode);
      }

      return exitCode;

    } catch (Exception e) {
      LOG.error(e, "Exception while running %s", command);
      MoreThrowables.propagateIfInterrupt(e);
      context.getConsole().printBuildFailureWithStacktrace(e);
      return 1;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(Iterables.transform(command, Escaper.SHELL_ESCAPER));
  }

  private static ImmutableList<String> createCommandArgs(
      Path xctoolPath,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(xctoolPath.toString());
    args.add("-reporter");
    args.add("json-stream");
    args.add("-sdk", sdkName);
    if (destinationSpecifier.isPresent()) {
      args.add("-destination");
      args.add(destinationSpecifier.get());
    }
    args.add("run-tests");
    for (Path logicTestBundlePath : logicTestBundlePaths) {
      args.add("-logicTest");
      args.add(logicTestBundlePath.toString());
    }
    for (Map.Entry<Path, Path> appTestBundleAndHostApp : appTestBundleToHostAppPaths.entrySet()) {
      args.add("-appTest");
      args.add(appTestBundleAndHostApp.getKey() + ":" + appTestBundleAndHostApp.getValue());
    }

    return args.build();
  }

  private static int waitForProcessAndGetExitCode(
      ProcessExecutor processExecutor,
      ProcessExecutor.LaunchedProcess launchedProcess)
      throws InterruptedException {
    int processExitCode = processExecutor.waitForLaunchedProcess(launchedProcess);
    if (processExitCode == 0 || processExitCode == 1) {
      // Test failure is denoted by xctool returning 1. Unfortunately, there's no way
      // to distinguish an internal xctool error from a test failure:
      //
      // https://github.com/facebook/xctool/issues/511
      //
      // We don't want to fail the step on a test failure, so return 0 on either
      // xctool exit code.
      return 0;
    } else {
      // Some unknown failure.
      return processExitCode;
    }
  }
}
