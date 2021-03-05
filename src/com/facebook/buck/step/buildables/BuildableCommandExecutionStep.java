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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link Step} that wraps a {@link BuildableCommand}. Writes the {@link BuildableCommand} into a
 * file, then invokes the given jar with the given main entry point, which can then read the {@link
 * BuildableCommand} file and execute its associated steps. Used as a bridge between {@link
 * com.facebook.buck.rules.modern.Buildable} and {@link
 * com.facebook.buck.rules.modern.BuildableWithExternalAction}.
 */
public class BuildableCommandExecutionStep extends IsolatedStep {

  private static final Logger LOG = Logger.get(BuildableCommandExecutionStep.class);

  private static final String TEMP_FILE_NAME_PREFIX = "buildable_command_";

  private final BuildableCommand buildableCommand;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableList<String> javaRuntimeLauncherCommand;
  private final Supplier<RelPath> externalActionsPathSupplier;

  /**
   * Used for testing only. Production code should use the constructor {@link
   * #BuildableCommandExecutionStep(BuildableCommand, ProjectFilesystem, ImmutableList, Supplier)}.
   */
  @VisibleForTesting
  public BuildableCommandExecutionStep(
      BuildableCommand buildableCommand,
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> javaCommandPrefix,
      Supplier<RelPath> externalActionsPathSupplier) {
    this.buildableCommand = buildableCommand;
    this.projectFilesystem = projectFilesystem;
    this.javaRuntimeLauncherCommand = javaCommandPrefix;
    this.externalActionsPathSupplier = externalActionsPathSupplier;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    Path buildableCommandPath = writeBuildableCommandAndGetPath();
    DownwardApiProcessExecutor downwardApiProcessExecutor = context.getDownwardApiProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(getCommand(context.getRuleCellRoot(), buildableCommandPath))
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
    Path buildableCommandPath = projectFilesystem.createTempFile(TEMP_FILE_NAME_PREFIX, "");
    try (OutputStream outputStream = new FileOutputStream(buildableCommandPath.toFile())) {
      buildableCommand.writeTo(outputStream);
    }
    return buildableCommandPath;
  }

  private ImmutableList<String> getCommand(AbsPath ruleCellRoot, Path buildableCommandPath)
      throws IOException {
    AbsPath jarPath = ruleCellRoot.resolve(externalActionsPathSupplier.get());
    if (!Files.exists(jarPath.getPath())) {
      throw new IOException("jar " + jarPath.toString() + " is not exist on env");
    }

    return ImmutableList.<String>builderWithExpectedSize(javaRuntimeLauncherCommand.size() + 3)
        .addAll(javaRuntimeLauncherCommand)
        .add("-jar")
        .add(jarPath.toString())
        .add(buildableCommandPath.toString())
        .build();
  }

  private ImmutableMap<String, String> getEnvs() {
    return ImmutableMap.of(
        ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT, projectFilesystem.getRootPath().toString());
  }
}
