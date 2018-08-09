/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.cli.BuildCommand;
import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.cli.ProjectSubCommand;
import com.facebook.buck.cli.parameter_extractors.ProjectGeneratorParameters;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public class XCodeProjectSubCommand extends ProjectSubCommand {

  private static final boolean DEFAULT_READ_ONLY_VALUE = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private boolean combinedProject;

  @Option(
      name = "--focus",
      usage =
          "Space separated list of build target full qualified names that should be part of "
              + "focused project. "
              + "For example, //Libs/CommonLibs:BaseLib //Libs/ImportantLib:ImportantLib")
  @Nullable
  private String modulesToFocusOn = null;

  @Option(
      name = "--read-only",
      usage =
          "If true, generate project files read-only. Defaults to '"
              + DEFAULT_READ_ONLY_VALUE
              + "' if not specified in .buckconfig. (Only "
              + "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
      name = "--show-full-output",
      usage = "Print the absolute path to the output for each of the built rules.")
  private boolean showFullOutput;

  @Option(
      name = "--show-output",
      usage = "Print the path to the output for each of the built rules relative to the cell.")
  private boolean showOutput;

  protected Mode getOutputMode() {
    if (this.showFullOutput) {
      return Mode.FULL;
    } else if (this.showOutput) {
      return Mode.SIMPLE;
    } else {
      return Mode.NONE;
    }
  }

  @Override
  public ExitCode run(
      CommandRunnerParams params,
      CommandThreadManager threadManager,
      ProjectGeneratorParameters projectGeneratorParameters,
      List<String> projectCommandArguments)
      throws IOException, InterruptedException {
    ListeningExecutorService executor = threadManager.getListeningExecutorService();
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        params
            .getCell()
            .getToolchainProvider()
            .getByName(AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);
    XCodeProjectCommandHelper xcodeProjectCommandHelper =
        new XCodeProjectCommandHelper(
            params.getBuckEventBus(),
            params.getPluginManager(),
            params.getParser(),
            params.getBuckConfig(),
            params.getVersionedTargetGraphCache(),
            params.getTypeCoercerFactory(),
            params.getCell(),
            params.getRuleKeyConfiguration(),
            params.getConsole(),
            params.getProcessManager(),
            params.getEnvironment(),
            params.getExecutors().get(ExecutorPool.PROJECT),
            projectCommandArguments,
            appleCxxPlatformsProvider.getAppleCxxPlatforms().getFlavors(),
            projectGeneratorParameters.getEnableParserProfiling(),
            projectGeneratorParameters.isWithTests(),
            projectGeneratorParameters.isWithoutTests(),
            projectGeneratorParameters.isWithoutDependenciesTests(),
            modulesToFocusOn,
            combinedProject,
            projectGeneratorParameters.isDryRun(),
            getReadOnly(params.getBuckConfig()),
            new PrintStreamPathOutputPresenter(
                params.getConsole().getStdOut(), getOutputMode(), params.getCell().getRoot()),
            projectGeneratorParameters.getArgsParser(),
            arguments -> {
              try {
                return runBuild(params, arguments);
              } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Cannot run a build", e);
              }
            });
    return xcodeProjectCommandHelper.parseTargetsAndRunXCodeGenerator(executor);
  }

  private ExitCode runBuild(CommandRunnerParams params, ImmutableList<String> arguments)
      throws IOException, InterruptedException {
    BuildCommand buildCommand = new BuildCommand(arguments);
    return buildCommand.run(params);
  }

  private boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  @Override
  public String getOptionValue() {
    return "xcode";
  }

  @Override
  public String getShortDescription() {
    return "project generation for XCode";
  }
}
