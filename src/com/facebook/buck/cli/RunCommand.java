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
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

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
  int runCommandWithOptionsInternal(RunCommandOptions options)
      throws IOException, InterruptedException {
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
    BuildTarget target = Iterables.getOnlyElement(getBuildTargets(ImmutableSet.of(targetName)));

    Build build = buildCommand.getBuild();
    BuildRule targetRule = build.getActionGraph().findBuildRuleByTarget(target);
    BinaryBuildRule binaryBuildRule = null;
    if (targetRule instanceof BinaryBuildRule) {
      binaryBuildRule = (BinaryBuildRule) targetRule;
    }
    if (binaryBuildRule == null) {
      console.printBuildFailure(
          "target " + targetName + " is not a binary rule (only binary rules can be `run`)");
      return 1;
    }

    // Ideally, we would take fullCommand, disconnect from NailGun, and run the command in the
    // user's shell. Currently, if you use `buck run` with buckd and ctrl-C to kill the command
    // being run, occasionally I get the following error when I try to run `buck run` again:
    //
    //   Daemon is busy, please wait or run "buck kill" to terminate it.
    //
    // Clearly something bad has happened here. If you are using `buck run` to start up a server
    // or some other process that is meant to "run forever," then it's pretty common to do:
    // `buck run`, test server, hit ctrl-C, edit server code, repeat. This should not wedge buckd.
    ImmutableList<String> fullCommand = new ImmutableList.Builder<String>()
        .addAll(binaryBuildRule.getExecutableCommand(getProjectFilesystem()))
        .addAll(options.getTargetArguments())
        .build();

    ShellStep step = new DefaultShellStep(fullCommand) {
      // Print the output from the step directly to stdout and stderr rather than buffering it and
      // printing it as two individual strings. This preserves the expected behavior where output
      // written to stdout and stderr may be interleaved when displayed in a terminal.
      @Override
      protected boolean shouldFlushStdOutErrAsProgressIsMade(Verbosity verbosity) {
        return true;
      }
    };
    return step.execute(build.getExecutionContext());
  }

  @Override
  RunCommandOptions createOptions(BuckConfig buckConfig) {
    return new RunCommandOptions(buckConfig);
  }
}
