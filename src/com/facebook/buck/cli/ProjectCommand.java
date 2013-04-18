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

import com.facebook.buck.command.Project;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.NoAndroidSdkException;
import com.google.common.base.Optional;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
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

  public ProjectCommand() {}

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(ProjectCommandOptions options) throws IOException {
    // Create a PartialGraph that only contains targets that can be represented as IDE
    // configuration files.
    PartialGraph partialGraph;
    try {
      partialGraph = PartialGraph.createPartialGraph(predicate,
          getProjectFilesystem().getProjectRoot(),
          options.getDefaultIncludes(),
          ansi);
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    Optional<AndroidPlatformTarget> androidPlatformTarget;
    try {
      androidPlatformTarget = options.findAndroidPlatformTarget(
          partialGraph.getDependencyGraph(), stdErr);
    } catch (NoAndroidSdkException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    ExecutionContext executionContext = new ExecutionContext(
        options.getVerbosity(),
        getProjectFilesystem().getProjectRoot(),
        androidPlatformTarget,
        options.findAndroidNdkDir(),
        ansi,
        false /* isCodeCoverageEnabled */,
        false /* isDebugEnabled */,
        stdOut,
        stdErr);

    Project project = new Project(partialGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest());

    File tempFile = new File(Files.createTempDir(), "project.json");
    int exitCode;
    try {
      exitCode = project.createIntellijProject(tempFile, console.getStdOut());
      if (exitCode != 0) {
        return exitCode;
      }

      // Build initial targets.
      if (options.hasInitialTargets()) {
        BuildCommand buildCommand = new BuildCommand(stdOut, stdErr, console, getProjectFilesystem());
        exitCode = buildCommand.runCommandWithOptions(
            options.createBuildCommandOptionsWithInitialTargets());

        if (exitCode != 0) {
          return exitCode;
        }
      }
    } finally {
      // Either leave project.json around for debugging or delete it on exit.
      if (options.getVerbosity().shouldPrintOutput()) {
        stdErr.printf("project.json was written to %s", tempFile.getAbsolutePath());
      } else {
        tempFile.deleteOnExit();
      }
    }

    return 0;
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }

}
