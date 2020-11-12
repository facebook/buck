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
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.external.constants.ExternalBinaryBuckConstants;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

/**
 * {@link Step} that wraps a {@link BuildableCommand}. Writes the {@link BuildableCommand} into a
 * file, then invokes the given jar with the given main entry point, which can then read the {@link
 * BuildableCommand} file and execute its associated steps. Used as a bridge between {@link
 * com.facebook.buck.rules.modern.Buildable} and {@link
 * com.facebook.buck.rules.modern.BuildableWithExternalAction}.
 */
public class BuildableCommandExecutionStep extends IsolatedStep {

  public static final String EXTERNAL_ACTIONS_PROPERTY_NAME = "buck.external_actions";
  private static final String EXTERNAL_ACTIONS_MAIN =
      "com.facebook.buck.external.main.ExternalActionsExecutable";
  private static final String TEMP_FILE_NAME_PREFIX = "buildable_command_";

  private final String mainToInvoke;
  private final BuildableCommand buildableCommand;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableList<String> javaCommandPrefix;

  public BuildableCommandExecutionStep(
      BuildableCommand buildableCommand,
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> javaCommandPrefix) {
    this(EXTERNAL_ACTIONS_MAIN, buildableCommand, projectFilesystem, javaCommandPrefix);
  }

  /**
   * Used for testing only. Production code should use the constructor {@link
   * #BuildableCommandExecutionStep(BuildableCommand, ProjectFilesystem, ImmutableList)}.
   */
  @VisibleForTesting
  public BuildableCommandExecutionStep(
      String mainToInvoke,
      BuildableCommand buildableCommand,
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> javaCommandPrefix) {
    this.mainToInvoke = mainToInvoke;
    this.buildableCommand = buildableCommand;
    this.projectFilesystem = projectFilesystem;
    this.javaCommandPrefix =
        ImmutableList.<String>builder().addAll(javaCommandPrefix).add("-cp").build();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    Path buildableCommandPath = writeBuildableCommandAndGetPath();
    ProcessExecutor downwardApiProcessExecutor =
        context
            .getProcessExecutor()
            .withDownwardAPI(DownwardApiProcessExecutor.FACTORY, context.getIsolatedEventBus());
    return StepExecutionResult.of(
        downwardApiProcessExecutor.launchAndExecute(
            ProcessExecutorParams.builder()
                .addAllCommand(getCommand(buildableCommandPath))
                .setEnvironment(getEnvs())
                .build(),
            ImmutableMap.of()));
  }

  @Override
  public String getShortName() {
    return "buildable_command";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return "running buildable command: " + buildableCommand;
  }

  /**
   * Returns the external action binary's JAR path. Protected visibility only for testing purposes,
   * otherwise private.
   */
  @VisibleForTesting
  protected AbsPath getJarPath() {
    return AbsPath.of(
        Paths.get(Preconditions.checkNotNull(getExternalActionsFromPropertiesOrNull())));
  }

  @Nullable
  public String getExternalActionsFromPropertiesOrNull() {
    return System.getProperty(EXTERNAL_ACTIONS_PROPERTY_NAME);
  }

  private Path writeBuildableCommandAndGetPath() throws IOException {
    Path buildableCommandPath = projectFilesystem.createTempFile(TEMP_FILE_NAME_PREFIX, "");
    try (OutputStream outputStream = new FileOutputStream(buildableCommandPath.toFile())) {
      buildableCommand.writeTo(outputStream);
    }
    return buildableCommandPath;
  }

  private ImmutableList<String> getCommand(Path buildableCommandPath) {
    return ImmutableList.<String>builderWithExpectedSize(javaCommandPrefix.size() + 3)
        .addAll(javaCommandPrefix)
        .add(getJarPath().toString())
        .add(mainToInvoke)
        .add(buildableCommandPath.toString())
        .build();
  }

  private ImmutableMap<String, String> getEnvs() {
    return ImmutableMap.of(
        ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT, projectFilesystem.getRootPath().toString());
  }
}
