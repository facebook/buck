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

package com.facebook.buck.step.buildables;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.external.constants.ExternalBinaryBuckConstants;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.environment.CommonChildProcessParams;
import com.facebook.buck.util.java.JavaRuntimeUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link Step} that wraps a {@link BuildableCommand}. Writes the {@link BuildableCommand} into a
 * file, then invokes the given jar with the given main entry point, which can then read the {@link
 * BuildableCommand} file and execute its associated steps. Used as a bridge between {@link
 * com.facebook.buck.rules.modern.Buildable} and {@link
 * com.facebook.buck.rules.modern.BuildableWithExternalAction}.
 */
public class BuildableCommandExecutionStep extends IsolatedStep {

  private static final Logger LOG = Logger.get(BuildableCommandExecutionStep.class);

  public static final String EXTERNAL_ACTIONS_MAIN_CLASS =
      "com.facebook.buck.external.main.ExternalActionsExecutableMain";

  private static final String TEMP_FILE_NAME_PREFIX = "buildable_command_";

  private final BuildableCommand buildableCommand;
  private final ProjectFilesystem projectFilesystem;
  private final String mainClass;

  /**
   * Used for testing only. Production code should use the constructor {@link #BuildableCommandExecutionStep(BuildableCommand, ProjectFilesystem).
   */
  @VisibleForTesting
  public BuildableCommandExecutionStep(
      BuildableCommand buildableCommand, ProjectFilesystem projectFilesystem, String mainClass) {
    this.buildableCommand = buildableCommand;
    this.projectFilesystem = projectFilesystem;
    this.mainClass = mainClass;
  }

  public BuildableCommandExecutionStep(
      BuildableCommand buildableCommand, ProjectFilesystem projectFilesystem) {
    this(buildableCommand, projectFilesystem, EXTERNAL_ACTIONS_MAIN_CLASS);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    Path buildableCommandPath = writeBuildableCommandAndGetPath();
    DownwardApiProcessExecutor downwardApiProcessExecutor = context.getDownwardApiProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(getCommand(buildableCommandPath))
            .setEnvironment(getEnvs())
            .build();

    Result executionResult =
        downwardApiProcessExecutor.launchAndExecute(
            processExecutorParams,
            ImmutableSet.<ProcessExecutor.Option>builder()
                .add(ProcessExecutor.Option.EXPECTING_STD_OUT)
                .add(ProcessExecutor.Option.EXPECTING_STD_ERR)
                .build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    StepExecutionResult stepExecutionResult = StepExecutionResult.of(executionResult);
    if (!stepExecutionResult.isSuccess()) {
      LOG.error(
          "External action: %s has been finished with exit code: %s%n Std out: %s%n Std err: %s%n",
          executionResult.getCommand(),
          executionResult.getExitCode(),
          executionResult.getStdout(),
          executionResult.getStderr());
    }
    return stepExecutionResult;
  }

  @Override
  public String getShortName() {
    return "buildable_command";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return "running buildable command: " + buildableCommand;
  }

  private Path writeBuildableCommandAndGetPath() throws IOException {
    Path buildableCommandPath =
        projectFilesystem.resolve(projectFilesystem.createTempFile(TEMP_FILE_NAME_PREFIX, ""));
    try (OutputStream outputStream = new FileOutputStream(buildableCommandPath.toFile())) {
      buildableCommand.writeTo(outputStream);
    }
    return buildableCommandPath;
  }

  private ImmutableList<String> getCommand(Path buildableCommandPath) {
    ImmutableList<String> commonJvmParams = getCommonJvmParams();

    String classpath =
        Objects.requireNonNull(
            BuckClasspath.getBuckBootstrapClasspathFromEnvVarOrNull(),
            BuckClasspath.BOOTSTRAP_ENV_VAR_NAME + " env variable is not set");
    ImmutableList<String> command =
        ImmutableList.of(
            "-cp",
            classpath,
            BuckClasspath.BOOTSTRAP_MAIN_CLASS,
            mainClass,
            buildableCommandPath.toString());

    return ImmutableList.<String>builderWithExpectedSize(
            1 + commonJvmParams.size() + command.size())
        .add(JavaRuntimeUtils.getBucksJavaBinCommand())
        .addAll(commonJvmParams)
        .addAll(command)
        .build();
  }

  @VisibleForTesting
  public static ImmutableList<String> getCommonJvmParams() {
    return ImmutableList.of(
        "-Dfile.encoding=" + UTF_8.name(),
        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
  }

  private ImmutableMap<String, String> getEnvs() {
    String ruleCellRoot = projectFilesystem.getRootPath().toString();
    return ImmutableMap.<String, String>builder()
        .put(ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT, ruleCellRoot)
        .putAll(CommonChildProcessParams.getCommonChildProcessEnvsIncludingBuckClasspath())
        .build();
  }
}
