/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs {@code idb} on one or more logic, ui or application tests */
public class IdbRunTestsStep implements Step {

  private static final Semaphore stutterLock = new Semaphore(1);
  private static final ScheduledExecutorService stutterTimeoutExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private static final Logger LOG = Logger.get(IdbRunTestsStep.class);

  private enum TestTypeEnum {
    LOGIC,
    APP,
    UI
  }

  /** Interface for reading the stdout of idb */
  public interface StdoutReadingCallback {
    void readStdout(InputStream stdout) throws IOException;
  }

  private final ProjectFilesystem filesystem;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;
  private final Optional<Long> idbStutterTimeout;
  private final Optional<Long> timeoutInMs;
  private final ImmutableList<String> command;
  private final ImmutableList<String> installCommand;
  private final ImmutableList<String> runCommand;

  public IdbRunTestsStep(
      Path idbPath,
      ProjectFilesystem filesystem,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      Optional<Long> idbStutterTimeout,
      Optional<Long> timeoutInMs,
      TestTypeEnum type,
      String testBundleId,
      Path testBundlePath,
      Optional<Path> appTestBundlePath,
      Optional<Path> testHostAppBundlePath) {
    this.filesystem = filesystem;
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
    this.idbStutterTimeout = idbStutterTimeout;
    this.timeoutInMs = timeoutInMs;
    ImmutableList.Builder<String> runCommandBuilder = ImmutableList.builder();
    this.installCommand =
        ImmutableList.of(idbPath.toString(), "xctest", "install", testBundlePath.toString());
    runCommandBuilder.add(idbPath.toString(), "xctest", "run");
    switch (type) {
      case LOGIC:
        runCommandBuilder.add("logic", testBundleId);
        break;
      case APP:
        runCommandBuilder.add("app", testBundleId, appTestBundlePath.toString());
        break;
      case UI:
        runCommandBuilder.add(
            "ui", testBundleId, appTestBundlePath.toString(), testHostAppBundlePath.toString());
        break;
    }
    runCommandBuilder.add("--json");
    this.runCommand = runCommandBuilder.build();

    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    for (String string : installCommand) {
      commandBuilder.add(string);
    }
    commandBuilder.add("&&");
    for (String string : runCommand) {
      commandBuilder.add(string);
    }
    this.command = commandBuilder.build();
  }

  /** Function that created one idb step for each different test */
  public static ImmutableList<IdbRunTestsStep> createCommands(
      Path idbPath,
      ProjectFilesystem filesystem,
      AppleBundle testBundle,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      Optional<Long> idbStutterTimeout,
      Optional<Long> timeoutInMs,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths) {
    String testBundleId = getAppleBundleId(testBundle, filesystem).get();
    ImmutableList.Builder<IdbRunTestsStep> commandBuilder = ImmutableList.builder();
    for (Path bundlePath : logicTestBundlePaths) {
      commandBuilder.add(
          new IdbRunTestsStep(
              idbPath,
              filesystem,
              outputPath,
              stdoutReadingCallback,
              idbStutterTimeout,
              timeoutInMs,
              TestTypeEnum.LOGIC,
              testBundleId,
              bundlePath,
              Optional.empty(),
              Optional.empty()));
    }
    for (Map.Entry<Path, Path> appTestBundleToHostAppPath :
        appTestBundleToHostAppPaths.entrySet()) {
      commandBuilder.add(
          new IdbRunTestsStep(
              idbPath,
              filesystem,
              outputPath,
              stdoutReadingCallback,
              idbStutterTimeout,
              timeoutInMs,
              TestTypeEnum.APP,
              testBundleId,
              appTestBundleToHostAppPath.getKey(),
              Optional.of(appTestBundleToHostAppPath.getValue()),
              Optional.empty()));
    }
    for (Map.Entry<Path, Map<Path, Path>> appTestPathToTestHostAppPathToTestTargetAppPath :
        appTestPathsToTestHostAppPathsToTestTargetAppPaths.entrySet()) {
      for (Map.Entry<Path, Path> testHostAppPathToTestTargetAppPath :
          appTestPathToTestHostAppPathToTestTargetAppPath.getValue().entrySet()) {
        commandBuilder.add(
            new IdbRunTestsStep(
                idbPath,
                filesystem,
                outputPath,
                stdoutReadingCallback,
                idbStutterTimeout,
                timeoutInMs,
                TestTypeEnum.UI,
                testBundleId,
                appTestPathToTestHostAppPathToTestTargetAppPath.getKey(),
                Optional.of(testHostAppPathToTestTargetAppPath.getValue()),
                Optional.of(testHostAppPathToTestTargetAppPath.getKey())));
      }
    }

    return commandBuilder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {

    // Installing the test
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(installCommand).build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result =
        context
            .getProcessExecutor()
            .launchAndExecute(
                processExecutorParams,
                options,
                /* stdin */ Optional.empty(),
                /* timeOutMs */ Optional.empty(),
                /* timeOutHandler */ Optional.empty());

    // Getting the result of the test
    if (result.getExitCode() != 0) {
      LOG.error("Could not install the test using idb");
      if (result.getStderr().isPresent()) {
        LOG.error(result.getStderr().get());
      }
      return StepExecutionResults.ERROR;
    }

    if (!result.getStdout().isPresent()) {
      LOG.error("Could not find the test bundle ID");
      return StepExecutionResults.ERROR;
    }

    processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(runCommand)
            .setDirectory(filesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();

    // Only launch one instance of idb at the time
    AtomicBoolean stutterLockIsNotified = new AtomicBoolean(false);
    try {
      LOG.debug("Running command: %s", processExecutorParams);

      acquireStutterLock(stutterLockIsNotified);

      // Start the process.
      ProcessExecutor.LaunchedProcess launchedProcess =
          context.getProcessExecutor().launchProcess(processExecutorParams);

      int exitCode = -1;
      String stderr = "Unexpected termination";
      try {
        ProcessStdoutReader stdoutReader = new ProcessStdoutReader(launchedProcess);
        ProcessStderrReader stderrReader = new ProcessStderrReader(launchedProcess);
        Thread stdoutReaderThread = new Thread(stdoutReader);
        Thread stderrReaderThread = new Thread(stderrReader);
        stdoutReaderThread.start();
        stderrReaderThread.start();
        exitCode =
            waitForProcessAndGetExitCode(
                context.getProcessExecutor(), launchedProcess, timeoutInMs);
        stdoutReaderThread.join(timeoutInMs.orElse(1000L));
        stderrReaderThread.join(timeoutInMs.orElse(1000L));
        Optional<IOException> exception = stdoutReader.getException();
        if (exception.isPresent()) {
          throw exception.get();
        }
        stderr = stderrReader.getStdErr();
        LOG.debug("Finished running command, exit code %d, stderr %s", exitCode, stderr);
      } finally {
        context.getProcessExecutor().destroyLaunchedProcess(launchedProcess);
        context.getProcessExecutor().waitForLaunchedProcess(launchedProcess);
      }

      if (exitCode != 0) {
        if (!stderr.isEmpty()) {
          context
              .getConsole()
              .printErrorText(
                  String.format(Locale.US, "idb failed with exit code %d: %s", exitCode, stderr));
        } else {
          context
              .getConsole()
              .printErrorText(String.format(Locale.US, "idb failed with exit code %d", exitCode));
        }
      }

      return StepExecutionResult.of(exitCode);

    } finally {
      releaseStutterLock(stutterLockIsNotified);
    }
  }

  private class ProcessStdoutReader implements Runnable {

    private final ProcessExecutor.LaunchedProcess launchedProcess;
    private Optional<IOException> exception = Optional.empty();

    public ProcessStdoutReader(ProcessExecutor.LaunchedProcess launchedProcess) {
      this.launchedProcess = launchedProcess;
    }

    @Override
    public void run() {
      try (OutputStream outputStream = filesystem.newFileOutputStream(outputPath);
          TeeInputStream stdoutWrapperStream =
              new TeeInputStream(launchedProcess.getStdout(), outputStream)) {
        if (stdoutReadingCallback.isPresent()) {
          // The caller is responsible for reading all the data, which TeeInputStream will
          // copy to outputStream.
          stdoutReadingCallback.get().readStdout(stdoutWrapperStream);
        } else {
          // Nobody's going to read from stdoutWrapperStream, so close it and copy
          // the process's stdout to outputPath directly.
          stdoutWrapperStream.close();
          ByteStreams.copy(launchedProcess.getStdout(), outputStream);
        }
      } catch (IOException e) {
        exception = Optional.of(e);
      }
    }

    public Optional<IOException> getException() {
      return exception;
    }
  }

  private static class ProcessStderrReader implements Runnable {

    private final ProcessExecutor.LaunchedProcess launchedProcess;
    private String stderr = "";

    public ProcessStderrReader(ProcessExecutor.LaunchedProcess launchedProcess) {
      this.launchedProcess = launchedProcess;
    }

    @Override
    public void run() {
      try (InputStreamReader stderrReader =
              new InputStreamReader(launchedProcess.getStderr(), StandardCharsets.UTF_8);
          BufferedReader bufferedStderrReader = new BufferedReader(stderrReader)) {
        stderr = CharStreams.toString(bufferedStderrReader).trim();
      } catch (IOException e) {
        stderr = Throwables.getStackTraceAsString(e);
      }
    }

    public String getStdErr() {
      return stderr;
    }
  }

  private static int waitForProcessAndGetExitCode(
      ProcessExecutor processExecutor,
      ProcessExecutor.LaunchedProcess launchedProcess,
      Optional<Long> timeoutInMs)
      throws InterruptedException {
    int processExitCode;
    if (timeoutInMs.isPresent()) {
      ProcessExecutor.Result processResult =
          processExecutor.waitForLaunchedProcessWithTimeout(
              launchedProcess, timeoutInMs.get(), Optional.empty());
      if (processResult.isTimedOut()) {
        throw new HumanReadableException(
            "Timed out after %d ms running test command", timeoutInMs.orElse(-1L));
      } else {
        processExitCode = processResult.getExitCode();
      }
    } else {
      processExitCode = processExecutor.waitForLaunchedProcess(launchedProcess).getExitCode();
    }
    if (processExitCode == 0) {
      return 0;
    } else {
      return processExitCode;
    }
  }

  private void acquireStutterLock(AtomicBoolean stutterLockIsNotified) throws InterruptedException {
    if (!idbStutterTimeout.isPresent()) {
      return;
    }
    try {
      stutterLock.acquire();
    } catch (Exception e) {
      releaseStutterLock(stutterLockIsNotified);
      throw e;
    }
    stutterTimeoutExecutorService.schedule(
        () -> releaseStutterLock(stutterLockIsNotified),
        idbStutterTimeout.get(),
        TimeUnit.MILLISECONDS);
  }

  private void releaseStutterLock(AtomicBoolean stutterLockIsNotified) {
    if (!idbStutterTimeout.isPresent()) {
      return;
    }
    if (!stutterLockIsNotified.getAndSet(true)) {
      stutterLock.release();
    }
  }

  /**
   * Gets the bundle ID of the installed bundle (the identifier of the bundle)
   *
   * @return the BundleId
   */
  private static Optional<String> getAppleBundleId(
      AppleBundle appleBundle, ProjectFilesystem projectFilesystem) {
    Optional<String> appleBundleId = Optional.empty();
    try (InputStream bundlePlistStream =
        projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())) {
      appleBundleId =
          AppleInfoPlistParsing.getBundleIdFromPlistStream(
              appleBundle.getInfoPlistPath(), bundlePlistStream);
    } catch (IOException e) {
      e.printStackTrace();
      LOG.error("Could not get apple bundle ID");
    }
    return appleBundleId;
  }

  public ImmutableList<String> getCommand() {
    return command;
  }

  @Override
  public String getShortName() {
    return "idb-run-test";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "idb";
  }
}
