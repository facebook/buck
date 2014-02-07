/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.apple.xcode.ProjectGenerator;
import com.facebook.buck.command.Project;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {

  /**
   * Predicate used to filter out all PROJECT_CONFIG rules in the dependency graph.
   */
  private static final RawRulePredicate predicate = new RawRulePredicate() {
    @Override
    public boolean isMatch(Map<String, Object> rawParseData,
        BuildRuleType buildRuleType,
        BuildTarget buildTarget) {
      return buildRuleType == BuildRuleType.PROJECT_CONFIG;
    }
  };


  public ProjectCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ProjectCommandOptions options) throws IOException {
    switch (options.getIde()) {
      case "intellij":
        return runIntellijProjectGenerator(options);
      case "xcode":
        return runXcodeProjectGenerator(options);
      default:
        throw new HumanReadableException(String.format(
            "Unknown IDE `%s` in .buckconfig", options.getIde()));
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(ProjectCommandOptions options) throws IOException {
    // Create a PartialGraph that only contains targets that can be represented as IDE
    // configuration files.
    PartialGraph partialGraph;

    try {
      partialGraph = createPartialGraph(predicate, options);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(options,
        partialGraph.getDependencyGraph());

    Project project = new Project(partialGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest(),
        options.getPathToPostProcessScript(),
        options.getBuckConfig().getPythonInterpreter());

    File tempDir = Files.createTempDir();
    File tempFile = new File(tempDir, "project.json");
    int exitCode;
    try {
      exitCode = createIntellijProject(project,
          tempFile,
          executionContext.getProcessExecutor(),
          console.getStdOut(),
          console.getStdErr());
      if (exitCode != 0) {
        return exitCode;
      }

      List<String> additionalInitialTargets = ImmutableList.of();

      // Build initial targets.
      if (options.hasInitialTargets() || !additionalInitialTargets.isEmpty()) {
        BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
        BuildCommandOptions buildOptions =
            options.createBuildCommandOptionsWithInitialTargets(additionalInitialTargets);


        exitCode = runBuildCommand(buildCommand, buildOptions);
        if (exitCode != 0) {
          return exitCode;
        }
      }
    } finally {
      // Either leave project.json around for debugging or delete it on exit.
      if (console.getVerbosity().shouldPrintOutput()) {
        getStdErr().printf("project.json was written to %s", tempFile.getAbsolutePath());
      } else {
        tempFile.delete();
        tempDir.delete();
      }
    }

    return 0;
  }


  /**
   * Calls {@link Project#createIntellijProject}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int createIntellijProject(Project project,
      File jsonTemplate,
      ProcessExecutor processExecutor,
      PrintStream stdOut,
      PrintStream stdErr)
      throws IOException {
    return project.createIntellijProject(jsonTemplate, processExecutor, stdOut, stdErr);
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(ProjectCommandOptions options)
      throws IOException {

    List<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();

    PartialGraph partialGraph;
    ImmutableList<BuildTarget> targets;
    try {
      // IOS creates a full graph all the time in order to find tests of targets (which depends
      // on, but is not depended on, by the libraries).
      partialGraph = PartialGraph.createFullGraph(getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
      targets = getBuildTargets(argumentsAsBuildTargets);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ProjectGenerator projectGenerator = new ProjectGenerator(
        partialGraph,
        targets,
        getProjectFilesystem(),
        getProjectFilesystem().getFileForRelativePath("_gen").toPath(),
        "GeneratedProject");

    projectGenerator.createXcodeProjects();
    return 0;
  }

  /**
   * Calls {@link BuildCommand#runCommandWithOptions}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int runBuildCommand(BuildCommand buildCommand, BuildCommandOptions options)
      throws IOException {
    return buildCommand.runCommandWithOptions(options);
  }

  @VisibleForTesting
  PartialGraph createPartialGraph(RawRulePredicate rulePredicate, ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, IOException {
    List<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();

    if (argumentsAsBuildTargets.isEmpty()) {
      return PartialGraph.createPartialGraph(
          rulePredicate,
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } else {
      // If build targets were specified, generate a partial intellij project that contains the
      // files needed to build the build targets specified.
      ImmutableList<BuildTarget> targets = getBuildTargets(argumentsAsBuildTargets);

      return PartialGraph.createPartialGraphFromRoots(targets,
          rulePredicate,
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    }
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }
}
