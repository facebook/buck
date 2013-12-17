/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.command.Build;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

public class RunCommand extends AbstractCommandRunner<RunCommandOptions> {

  public RunCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  String getUsageIntro() {
    return "runs the specified target as a command, with provided args";
  }

  @Override
  int runCommandWithOptionsInternal(RunCommandOptions options) throws IOException {
    if (!options.hasTargetSpecified()) {
      console.printBuildFailure("No target given to run");
      console.getStdOut().println("buck run <target> <arg1> <arg2>...");
      return 1;
    }

    // Make sure the target is built.
    BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
    BuildCommandOptions buildCommandOptions = new BuildCommandOptions(options.getBuckConfig());
    buildCommandOptions.setArguments(ImmutableList.of(options.getTarget()));
    int exitCode = buildCommand.runCommandWithOptions(buildCommandOptions);
    if (exitCode != 0) {
      return exitCode;
    }

    String targetName = options.getTarget();
    BuildTarget target;
    try {
      target = getBuildTargets(ImmutableList.of(targetName)).get(0);
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailure(e.getMessage());
      return 1;
    }

    Build build = buildCommand.getBuild();
    BuildRule targetRule = build.getDependencyGraph().findBuildRuleByTarget(target);
    if (!(targetRule instanceof BinaryBuildRule)) {
      console.printBuildFailure(
            "target " + targetName + " is not a binary rule (only binary rules can be `run`)"
      );
      return 1;
    }

    ImmutableList<String> fullCommand = new ImmutableList.Builder<String>()
        .addAll(((BinaryBuildRule)targetRule).getExecutableCommand(getProjectFilesystem()))
        .addAll(options.getTargetArguments())
        .build();
    ShellStep step = new DefaultShellStep(fullCommand);
    return step.execute(build.getExecutionContext());
  }

  @Override
  RunCommandOptions createOptions(BuckConfig buckConfig) {
    return new RunCommandOptions(buckConfig);
  }
}
