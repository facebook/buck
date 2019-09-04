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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runs {@code idb} on one or more logic, ui or application tests */
public class IdbRunTestsStep implements Step {

  private static final Logger LOG = Logger.get(IdbRunTestsStep.class);

  private enum TestTypeEnum {
    LOGIC,
    APP,
    UI
  }

  private final ImmutableList<String> command;
  private final ImmutableList<String> installCommand;
  private final ImmutableList<String> runCommand;

  public IdbRunTestsStep(
      Path idbPath,
      TestTypeEnum type,
      String testBundleId,
      Path testBundlePath,
      Optional<Path> appTestBundlePath,
      Optional<Path> testHostAppBundlePath) {

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
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths) {
    String testBundleId = getAppleBundleId(testBundle, filesystem).get();
    ImmutableList.Builder<IdbRunTestsStep> commandBuilder = ImmutableList.builder();
    for (Path bundlePath : logicTestBundlePaths) {
      commandBuilder.add(
          new IdbRunTestsStep(
              idbPath,
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

    processExecutorParams = ProcessExecutorParams.builder().setCommand(runCommand).build();
    result =
        context
            .getProcessExecutor()
            .launchAndExecute(
                processExecutorParams,
                options,
                /* stdin */ Optional.empty(),
                /* timeOutMs */ Optional.empty(),
                /* timeOutHandler */ Optional.empty());

    if (result.getExitCode() != 0) {
      LOG.error("Could not execute test");
      if (result.getStderr().isPresent()) {
        LOG.error(result.getStderr().get());
      }
      return StepExecutionResults.ERROR;
    }

    System.out.println(result.getStdout().get());
    return StepExecutionResults.SUCCESS;
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
