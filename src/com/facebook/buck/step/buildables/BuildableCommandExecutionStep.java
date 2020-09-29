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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link Step} that wraps a {@link BuildableCommand}. Writes the {@link BuildableCommand} into a
 * file, then invokes the given jar with the given main entry point, which can then read the {@link
 * BuildableCommand} file and execute its associated steps. Used as a bridge between {@link
 * com.facebook.buck.rules.modern.Buildable} and {@link
 * com.facebook.buck.rules.modern.BuildableWithExternalAction}.
 */
public class BuildableCommandExecutionStep extends IsolatedStep {

  private static final String EXTERNAL_ACTIONS_RESOURCE_JAR =
      "com/facebook/buck/external/main/external_actions_bin.jar";
  private static final String EXTERNAL_ACTIONS_MAIN =
      "com.facebook.buck.external.main.ExternalActionsExecutable";
  private static final ImmutableList<String> COMMAND_PREFIX = ImmutableList.of("java", "-cp");
  private static final String TEMP_FILE_NAME_PREFIX = "buildable_command_";

  private final AbsPath jarPath;
  private final String mainToInvoke;
  private final BuildableCommand buildableCommand;
  private final ProjectFilesystem projectFilesystem;

  public BuildableCommandExecutionStep(
      BuildableCommand buildableCommand, ProjectFilesystem projectFilesystem) {
    this(getJarPath(), EXTERNAL_ACTIONS_MAIN, buildableCommand, projectFilesystem);
  }

  /**
   * Used for testing only. Production code should use the constructor {@link
   * #BuildableCommandExecutionStep(BuildableCommand, ProjectFilesystem)}.
   */
  @VisibleForTesting
  public BuildableCommandExecutionStep(
      AbsPath jarPath,
      String mainToInvoke,
      BuildableCommand buildableCommand,
      ProjectFilesystem projectFilesystem) {
    this.jarPath = jarPath;
    this.mainToInvoke = mainToInvoke;
    this.buildableCommand = buildableCommand;
    this.projectFilesystem = projectFilesystem;
  }

  private static AbsPath getJarPath() {
    try {
      URL binary = Resources.getResource(EXTERNAL_ACTIONS_RESOURCE_JAR);
      return AbsPath.of(Paths.get(binary.toURI()));
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Failed to retrieve external actions binary", e);
    }
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

  private Path writeBuildableCommandAndGetPath() throws IOException {
    Path buildableCommandPath = projectFilesystem.createTempFile(TEMP_FILE_NAME_PREFIX, "");
    try (OutputStream outputStream = new FileOutputStream(buildableCommandPath.toFile())) {
      buildableCommand.writeTo(outputStream);
    }
    return buildableCommandPath;
  }

  private ImmutableList<String> getCommand(Path buildableCommandPath) {
    return ImmutableList.<String>builderWithExpectedSize(
            COMMAND_PREFIX.size() + buildableCommand.getArgsList().size())
        .addAll(COMMAND_PREFIX)
        .add(jarPath.toString())
        .add(mainToInvoke)
        .add(buildableCommandPath.toString())
        .build();
  }

  private ImmutableMap<String, String> getEnvs() {
    return ImmutableMap.of(
        ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT, projectFilesystem.getRootPath().toString());
  }
}
