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

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Runs {@code xctool} on one or more logic tests and/or application tests (each paired with a host
 * application).
 *
 * <p>The output is written in streaming JSON format to stdout and is parsed by {@link
 * XctoolOutputParsing}.
 */
class XctoolRunTestsStep implements Step {

  private static final Semaphore stutterLock = new Semaphore(1);
  private static final ScheduledExecutorService stutterTimeoutExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private static final String XCTOOL_ENV_VARIABLE_PREFIX = "XCTOOL_TEST_ENV_";
  private static final String FB_REFERENCE_IMAGE_DIR = "FB_REFERENCE_IMAGE_DIR";

  private final ProjectFilesystem filesystem;

  public interface StdoutReadingCallback {
    void readStdout(InputStream stdout) throws IOException;
  }

  private static final Logger LOG = Logger.get(XctoolRunTestsStep.class);

  private final ImmutableList<String> command;
  private final ImmutableMap<String, String> environmentOverrides;
  private final Optional<Long> xctoolStutterTimeout;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;
  private final AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider;
  private final TestSelectorList testSelectorList;
  private final Optional<String> logDirectoryEnvironmentVariable;
  private final Optional<Path> logDirectory;
  private final Optional<String> logLevelEnvironmentVariable;
  private final Optional<String> logLevel;
  private final Optional<Long> timeoutInMs;
  private final Optional<String> snapshotReferenceImagesPath;

  // Helper class to parse the output of `xctool -listTestsOnly` then
  // store it in a multimap of {target: [testDesc1, testDesc2, ...], ... } pairs.
  //
  // We need to remember both the target name and the test class/method names, since
  // `xctool -only` requires the format `TARGET:className/methodName,...`
  private static class ListTestsOnlyHandler implements XctoolOutputParsing.XctoolEventCallback {
    private @Nullable String currentTestTarget;
    public Multimap<String, TestDescription> testTargetsToDescriptions;

    public ListTestsOnlyHandler() {
      this.currentTestTarget = null;
      // We use a LinkedListMultimap to make the order deterministic for testing.
      this.testTargetsToDescriptions = LinkedListMultimap.create();
    }

    @Override
    public void handleBeginOcunitEvent(XctoolOutputParsing.BeginOcunitEvent event) {
      // Signals the start of listing all tests belonging to a single target.
      this.currentTestTarget = event.targetName;
    }

    @Override
    public void handleEndOcunitEvent(XctoolOutputParsing.EndOcunitEvent event) {
      Preconditions.checkNotNull(this.currentTestTarget);
      Preconditions.checkState(this.currentTestTarget.equals(event.targetName));
      // Signals the end of listing all tests belonging to a single target.
      this.currentTestTarget = null;
    }

    @Override
    public void handleBeginTestSuiteEvent(XctoolOutputParsing.BeginTestSuiteEvent event) {}

    @Override
    public void handleEndTestSuiteEvent(XctoolOutputParsing.EndTestSuiteEvent event) {}

    @Override
    public void handleBeginStatusEvent(XctoolOutputParsing.StatusEvent event) {}

    @Override
    public void handleEndStatusEvent(XctoolOutputParsing.StatusEvent event) {}

    @Override
    public void handleBeginTestEvent(XctoolOutputParsing.BeginTestEvent event) {
      testTargetsToDescriptions.put(
          Preconditions.checkNotNull(this.currentTestTarget),
          new TestDescription(
              Preconditions.checkNotNull(event.className),
              Preconditions.checkNotNull(event.methodName)));
    }

    @Override
    public void handleEndTestEvent(XctoolOutputParsing.EndTestEvent event) {}
  }

  public XctoolRunTestsStep(
      ProjectFilesystem filesystem,
      Path xctoolPath,
      ImmutableMap<String, String> environmentOverrides,
      Optional<Long> xctoolStutterTimeout,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider,
      TestSelectorList testSelectorList,
      boolean waitForDebugger,
      Optional<String> logDirectoryEnvironmentVariable,
      Optional<Path> logDirectory,
      Optional<String> logLevelEnvironmentVariable,
      Optional<String> logLevel,
      Optional<Long> timeoutInMs,
      Optional<String> snapshotReferenceImagesPath) {
    Preconditions.checkArgument(
        !(logicTestBundlePaths.isEmpty()
            && appTestBundleToHostAppPaths.isEmpty()
            && appTestPathsToTestHostAppPathsToTestTargetAppPaths.isEmpty()),
        "Either logic tests (%s) or app tests (%s) or uitest (%s) must be present",
        logicTestBundlePaths,
        appTestBundleToHostAppPaths,
        appTestPathsToTestHostAppPathsToTestTargetAppPaths);

    this.filesystem = filesystem;

    this.command =
        createCommandArgs(
            xctoolPath,
            sdkName,
            destinationSpecifier,
            logicTestBundlePaths,
            appTestBundleToHostAppPaths,
            appTestPathsToTestHostAppPathsToTestTargetAppPaths,
            waitForDebugger);
    this.environmentOverrides = environmentOverrides;
    this.xctoolStutterTimeout = xctoolStutterTimeout;
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
    this.appleDeveloperDirectoryForTestsProvider = appleDeveloperDirectoryForTestsProvider;
    this.testSelectorList = testSelectorList;
    this.logDirectoryEnvironmentVariable = logDirectoryEnvironmentVariable;
    this.logDirectory = logDirectory;
    this.logLevelEnvironmentVariable = logLevelEnvironmentVariable;
    this.logLevel = logLevel;
    this.timeoutInMs = timeoutInMs;
    this.snapshotReferenceImagesPath = snapshotReferenceImagesPath;
  }

  @Override
  public String getShortName() {
    return "xctool-run-tests";
  }

  public ImmutableMap<String, String> getEnv(ExecutionContext context) {
    Map<String, String> environment = new HashMap<>();
    environment.putAll(context.getEnvironment());
    Path xcodeDeveloperDir =
        appleDeveloperDirectoryForTestsProvider.getAppleDeveloperDirectoryForTests();
    environment.put("DEVELOPER_DIR", xcodeDeveloperDir.toString());
    // xctool will only pass through to the test environment variables whose names
    // start with `XCTOOL_TEST_ENV_`. (It will remove that prefix when passing them
    // to the test.)
    if (logDirectoryEnvironmentVariable.isPresent() && logDirectory.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logDirectoryEnvironmentVariable.get(),
          logDirectory.get().toString());
    }
    if (logLevelEnvironmentVariable.isPresent() && logLevel.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logLevelEnvironmentVariable.get(), logLevel.get());
    }
    if (snapshotReferenceImagesPath.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + FB_REFERENCE_IMAGE_DIR, snapshotReferenceImagesPath.get());
    }

    environment.putAll(this.environmentOverrides);
    return ImmutableMap.copyOf(environment);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableMap<String, String> env = getEnv(context);

    ProcessExecutorParams.Builder processExecutorParamsBuilder =
        ProcessExecutorParams.builder()
            .addAllCommand(command)
            .setDirectory(filesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .setEnvironment(env);

    if (!testSelectorList.isEmpty()) {
      ImmutableList.Builder<String> xctoolFilterParamsBuilder = ImmutableList.builder();
      int returnCode =
          listAndFilterTestsThenFormatXctoolParams(
              context.getProcessExecutor(),
              context.getConsole(),
              testSelectorList,
              // Copy the entire xctool command and environment but add a -listTestsOnly arg.
              ProcessExecutorParams.builder()
                  .from(processExecutorParamsBuilder.build())
                  .addCommand("-listTestsOnly")
                  .build(),
              xctoolFilterParamsBuilder);
      if (returnCode != 0) {
        context.getConsole().printErrorText("Failed to query tests with xctool");
        return StepExecutionResult.of(returnCode);
      }
      ImmutableList<String> xctoolFilterParams = xctoolFilterParamsBuilder.build();
      if (xctoolFilterParams.isEmpty()) {
        context
            .getConsole()
            .printBuildFailure(
                String.format(
                    Locale.US,
                    "No tests found matching specified filter (%s)",
                    testSelectorList.getExplanation()));
        return StepExecutionResults.SUCCESS;
      }
      processExecutorParamsBuilder.addAllCommand(xctoolFilterParams);
    }

    ProcessExecutorParams processExecutorParams = processExecutorParamsBuilder.build();

    // Only launch one instance of xctool at the time
    final AtomicBoolean stutterLockIsNotified = new AtomicBoolean(false);
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
                  String.format(
                      Locale.US, "xctool failed with exit code %d: %s", exitCode, stderr));
        } else {
          context
              .getConsole()
              .printErrorText(
                  String.format(Locale.US, "xctool failed with exit code %d", exitCode));
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
              new TeeInputStream(launchedProcess.getInputStream(), outputStream)) {
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
              new InputStreamReader(launchedProcess.getErrorStream(), StandardCharsets.UTF_8);
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

  @Override
  public String getDescription(ExecutionContext context) {
    return command.stream().map(Escaper.SHELL_ESCAPER).collect(Collectors.joining(" "));
  }

  private static int listAndFilterTestsThenFormatXctoolParams(
      ProcessExecutor processExecutor,
      Console console,
      TestSelectorList testSelectorList,
      ProcessExecutorParams listTestsOnlyParams,
      ImmutableList.Builder<String> filterParamsBuilder)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(!testSelectorList.isEmpty());
    LOG.debug("Filtering tests with selector list: %s", testSelectorList.getExplanation());

    LOG.debug("Listing tests with command: %s", listTestsOnlyParams);
    ProcessExecutor.LaunchedProcess launchedProcess =
        processExecutor.launchProcess(listTestsOnlyParams);

    ListTestsOnlyHandler listTestsOnlyHandler = new ListTestsOnlyHandler();
    String stderr;
    int listTestsResult;
    try (InputStreamReader isr =
            new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        InputStreamReader esr =
            new InputStreamReader(launchedProcess.getErrorStream(), StandardCharsets.UTF_8);
        BufferedReader ebr = new BufferedReader(esr)) {
      XctoolOutputParsing.streamOutputFromReader(br, listTestsOnlyHandler);
      stderr = CharStreams.toString(ebr).trim();
      listTestsResult = processExecutor.waitForLaunchedProcess(launchedProcess).getExitCode();
    }

    if (listTestsResult != 0) {
      if (!stderr.isEmpty()) {
        console.printErrorText(
            String.format(
                Locale.US, "xctool failed with exit code %d: %s", listTestsResult, stderr));
      } else {
        console.printErrorText(
            String.format(Locale.US, "xctool failed with exit code %d", listTestsResult));
      }
    } else {
      formatXctoolFilterParams(
          testSelectorList, listTestsOnlyHandler.testTargetsToDescriptions, filterParamsBuilder);
    }

    return listTestsResult;
  }

  private static void formatXctoolFilterParams(
      TestSelectorList testSelectorList,
      Multimap<String, TestDescription> testTargetsToDescriptions,
      ImmutableList.Builder<String> filterParamsBuilder) {
    for (String testTarget : testTargetsToDescriptions.keySet()) {
      StringBuilder sb = new StringBuilder();
      boolean matched = false;
      for (TestDescription testDescription : testTargetsToDescriptions.get(testTarget)) {
        if (!testSelectorList.isIncluded(testDescription)) {
          continue;
        }
        if (!matched) {
          matched = true;
          sb.append(testTarget);
          sb.append(':');
        } else {
          sb.append(',');
        }
        sb.append(testDescription.getClassName());
        sb.append('/');
        sb.append(testDescription.getMethodName());
      }
      if (matched) {
        filterParamsBuilder.add("-only");
        filterParamsBuilder.add(sb.toString());
      }
    }
  }

  private static ImmutableList<String> createCommandArgs(
      Path xctoolPath,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      boolean waitForDebugger) {
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

    for (Map.Entry<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetApp :
        appTestPathsToTestHostAppPathsToTestTargetAppPaths.entrySet()) {
      for (Map.Entry<Path, Path> testHostAppToTestTargetApp :
          appTestPathsToTestHostAppPathsToTestTargetApp.getValue().entrySet()) {
        args.add("-uiTest");
        args.add(
            appTestPathsToTestHostAppPathsToTestTargetApp.getKey()
                + ":"
                + testHostAppToTestTargetApp.getKey()
                + ":"
                + testHostAppToTestTargetApp.getValue());
      }
    }
    if (waitForDebugger) {
      args.add("-waitForDebugger");
    }

    return args.build();
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

  private void acquireStutterLock(final AtomicBoolean stutterLockIsNotified)
      throws InterruptedException {
    if (!xctoolStutterTimeout.isPresent()) {
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
        xctoolStutterTimeout.get(),
        TimeUnit.MILLISECONDS);
  }

  private void releaseStutterLock(AtomicBoolean stutterLockIsNotified) {
    if (!xctoolStutterTimeout.isPresent()) {
      return;
    }
    if (!stutterLockIsNotified.getAndSet(true)) {
      stutterLock.release();
    }
  }

  public ImmutableList<String> getCommand() {
    return command;
  }
}
