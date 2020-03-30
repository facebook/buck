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

package com.facebook.buck.apple;

import com.facebook.buck.apple.simulator.AppleDeviceController;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
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
import java.nio.file.Paths;
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

  private final Path idbPath;
  private final ProjectFilesystem filesystem;
  private final String sdkName;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;
  private final TestSelectorList testSelectorList;
  private final Optional<Long> idbStutterTimeout;
  private final Optional<Long> timeoutInMs;
  private final String testBundleId;
  private final TestTypeEnum type;
  private final Optional<Path> appTestBundlePath;
  private final Optional<Path> testHostAppBundlePath;
  private final ImmutableList<String> command;
  private final ImmutableList<String> installCommand;
  private final ImmutableList<String> runCommand;
  private final Optional<String> deviceUdid;

  public IdbRunTestsStep(
      Path idbPath,
      ProjectFilesystem filesystem,
      String sdkName,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      TestSelectorList testSelectorList,
      Optional<Long> idbStutterTimeout,
      Optional<Long> timeoutInMs,
      TestTypeEnum type,
      String testBundleId,
      Path testBundlePath,
      Optional<Path> appTestBundlePath,
      Optional<Path> testHostAppBundlePath,
      Optional<String> deviceUdid) {
    this.idbPath = idbPath;
    this.filesystem = filesystem;
    this.sdkName = sdkName;
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
    this.testSelectorList = testSelectorList;
    this.idbStutterTimeout = idbStutterTimeout;
    this.timeoutInMs = timeoutInMs;
    this.testBundleId = testBundleId;
    this.type = type;
    this.appTestBundlePath = parseBuckAppPath(appTestBundlePath);
    this.testHostAppBundlePath = parseBuckAppPath(testHostAppBundlePath);
    this.deviceUdid = deviceUdid;
    ImmutableList.Builder<String> installCommandBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> runCommandBuilder = ImmutableList.builder();
    installCommandBuilder.add(idbPath.toString(), "xctest", "install", testBundlePath.toString());
    runCommandBuilder.add(idbPath.toString(), "xctest", "run");
    switch (type) {
      case LOGIC:
        runCommandBuilder.add("logic");
        break;
      case APP:
        runCommandBuilder.add("app");
        break;
      case UI:
        runCommandBuilder.add("ui");
        break;
    }
    runCommandBuilder.add("--json");
    if (deviceUdid.isPresent()) {
      installCommandBuilder.add("--udid", deviceUdid.get());
      runCommandBuilder.add("--udid", deviceUdid.get());
    }
    this.installCommand = installCommandBuilder.build();
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
      String sdkName,
      AppleBundle testBundle,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      TestSelectorList testSelectorList,
      Optional<Long> idbStutterTimeout,
      Optional<Long> timeoutInMs,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      Optional<String> deviceUdid) {
    String testBundleId = getAppleBundleId(testBundle, filesystem).get();
    ImmutableList.Builder<IdbRunTestsStep> commandBuilder = ImmutableList.builder();
    for (Path bundlePath : logicTestBundlePaths) {
      commandBuilder.add(
          new IdbRunTestsStep(
              idbPath,
              filesystem,
              sdkName,
              outputPath,
              stdoutReadingCallback,
              testSelectorList,
              idbStutterTimeout,
              timeoutInMs,
              TestTypeEnum.LOGIC,
              testBundleId,
              bundlePath,
              Optional.empty(),
              Optional.empty(),
              deviceUdid));
    }
    for (Map.Entry<Path, Path> appTestBundleToHostAppPath :
        appTestBundleToHostAppPaths.entrySet()) {
      commandBuilder.add(
          new IdbRunTestsStep(
              idbPath,
              filesystem,
              sdkName,
              outputPath,
              stdoutReadingCallback,
              testSelectorList,
              idbStutterTimeout,
              timeoutInMs,
              TestTypeEnum.APP,
              testBundleId,
              appTestBundleToHostAppPath.getKey(),
              Optional.of(appTestBundleToHostAppPath.getValue()),
              Optional.empty(),
              deviceUdid));
    }
    for (Map.Entry<Path, Map<Path, Path>> appTestPathToTestHostAppPathToTestTargetAppPath :
        appTestPathsToTestHostAppPathsToTestTargetAppPaths.entrySet()) {
      for (Map.Entry<Path, Path> testHostAppPathToTestTargetAppPath :
          appTestPathToTestHostAppPathToTestTargetAppPath.getValue().entrySet()) {
        commandBuilder.add(
            new IdbRunTestsStep(
                idbPath,
                filesystem,
                sdkName,
                outputPath,
                stdoutReadingCallback,
                testSelectorList,
                idbStutterTimeout,
                timeoutInMs,
                TestTypeEnum.UI,
                testBundleId,
                appTestPathToTestHostAppPathToTestTargetAppPath.getKey(),
                Optional.of(testHostAppPathToTestTargetAppPath.getValue()),
                Optional.of(testHostAppPathToTestTargetAppPath.getKey()),
                deviceUdid));
      }
    }

    return commandBuilder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {

    // Verify if there is the udid of the device
    if (!deviceUdid.isPresent()) {
      if (type == TestTypeEnum.LOGIC) {
        LOG.warn(
            "Could not find any simulator to run the tests, this will cause a slower execution");
      } else throw new HumanReadableException("Cannot run app or ui tests without a simulator");
    }

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

    // Booting the simulator for the test (if not mac)
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(context.getProcessExecutor(), idbPath);
    if (deviceUdid.isPresent() && sdkName.contains("iphone")) {
      appleDeviceController.bootSimulator(deviceUdid.get());
    }

    // Install necessary apps for the tests
    Optional<String> testApp = Optional.empty();
    if (type == TestTypeEnum.APP || type == TestTypeEnum.UI) {
      if (!appTestBundlePath.isPresent()) {
        LOG.error("Could not find the path to the test app");
        return StepExecutionResults.ERROR;
      }
      testApp = appleDeviceController.installBundle(deviceUdid.get(), appTestBundlePath.get());
      if (!testApp.isPresent()) {
        LOG.error("Could not install the test app");
        return StepExecutionResults.ERROR;
      }
    }
    Optional<String> hostTestApp = Optional.empty();
    if (type == TestTypeEnum.UI) {
      if (!testHostAppBundlePath.isPresent()) {
        LOG.error("Could not find the path to the host app");
        return StepExecutionResults.ERROR;
      }
      hostTestApp =
          appleDeviceController.installBundle(deviceUdid.get(), testHostAppBundlePath.get());
      if (!hostTestApp.isPresent()) {
        LOG.error("Could not install the host test app");
        return StepExecutionResults.ERROR;
      }
    }

    // Preparing the run test command
    ProcessExecutorParams.Builder processExecutorParamsBuilder =
        ProcessExecutorParams.builder()
            .setCommand(runCommand)
            .setDirectory(filesystem.getRootPath().getPath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE);

    Console console = context.getConsole();
    if (!testSelectorList.isEmpty()) {
      ImmutableList.Builder<String> idbFilterParamsBuilder = ImmutableList.builder();
      int returnCode =
          listAndFilterTestThenFormatIdbParams(
              context.getProcessExecutor(), testSelectorList, idbFilterParamsBuilder);
      if (returnCode != 0) {
        console.printErrorText("Failed to query tests with idb");
        return StepExecutionResult.of(returnCode);
      }
      ImmutableList<String> idbFilterParams = idbFilterParamsBuilder.build();
      if (idbFilterParams.isEmpty()) {
        console.printBuildFailure(
            String.format(
                Locale.US,
                "No tests found matching specified filter (%s)",
                testSelectorList.getExplanation()));
        return StepExecutionResults.SUCCESS;
      }
      processExecutorParamsBuilder.addAllCommand(idbFilterParams);
    }

    switch (type) {
      case LOGIC:
        processExecutorParamsBuilder.addCommand(testBundleId);
        break;
      case APP:
        processExecutorParamsBuilder.addCommand(testBundleId, testApp.get());
        break;
      case UI:
        processExecutorParamsBuilder.addCommand(testBundleId, testApp.get(), hostTestApp.get());
        break;
    }

    processExecutorParams = processExecutorParamsBuilder.build();

    // Only launch one instance of idb at the time
    AtomicBoolean stutterLockIsNotified = new AtomicBoolean(false);
    try {
      LOG.debug("Running command: %s", processExecutorParams);

      acquireStutterLock(stutterLockIsNotified);

      // Start the process.
      ProcessExecutor.LaunchedProcess launchedProcess =
          context.getProcessExecutor().launchProcess(processExecutorParams);

      int exitCode;
      String stderr;
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

      if (exitCode != StepExecutionResults.SUCCESS_EXIT_CODE) {
        if (!stderr.isEmpty()) {
          console.printErrorText(
              String.format(Locale.US, "idb failed with exit code %d: %s", exitCode, stderr));
        } else {
          console.printErrorText(
              String.format(Locale.US, "idb failed with exit code %d", exitCode));
        }
      }

      return StepExecutionResult.builder()
          .setExitCode(exitCode)
          .setExecutedCommand(launchedProcess.getCommand())
          .setStderr(Optional.ofNullable(stderr))
          .build();

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

  private int listAndFilterTestThenFormatIdbParams(
      ProcessExecutor processExecutor,
      TestSelectorList testSelectorList,
      ImmutableList.Builder<String> filterParamsBuilder)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(!testSelectorList.isEmpty());
    LOG.debug("Filtering tests with selector list: %s", testSelectorList.getExplanation());

    // Getting tests from the testBundle
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.add(idbPath.toString(), "xctest", "list-bundle", testBundleId);
    if (type == TestTypeEnum.APP) {
      commandBuilder.add("--app-path", appTestBundlePath.get().toString());
    }
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(commandBuilder.build()).build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      LOG.error("Could not get tests from testBundle");
      return result.getExitCode();
    }
    if (!result.getStdout().isPresent()) {
      LOG.error("Could not find any tests in the testBundle");
      return 1;
    }
    String[] testsInBundle = result.getStdout().get().split("\n");

    // Checking to see if the selected tests are in the bundle
    formatIdbTestListAndFilter(testsInBundle, testSelectorList, filterParamsBuilder);

    return result.getExitCode();
  }

  private void formatIdbTestListAndFilter(
      String[] testsInBundle,
      TestSelectorList testSelectorList,
      ImmutableList.Builder<String> filterParamsBuilder) {
    boolean matched = false;
    for (String test : testsInBundle) {
      String[] testName = test.split("/");
      TestDescription testDescription = new TestDescription(testName[0], testName[1]);
      if (!testSelectorList.isIncluded(testDescription)) {
        continue;
      }
      if (!matched) {
        matched = true;
        filterParamsBuilder.add("--tests-to-run");
      }
      filterParamsBuilder.add(test);
    }
    if (matched) {
      filterParamsBuilder.add("--");
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

  /** Parses the path of the app buck creates in order to pass it to idb */
  private static Optional<Path> parseBuckAppPath(Optional<Path> path) {
    if (!path.isPresent()) {
      return Optional.empty();
    }
    String stringPath = path.get().toString().split(".app")[0];
    return Optional.of(Paths.get(stringPath.concat(".app")));
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
