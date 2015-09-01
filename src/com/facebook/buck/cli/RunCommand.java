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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.List;

public class RunCommand extends AbstractCommand {

  /**
   * Expected usage:
   * <pre>
   *   buck run //java/com/facebook/tools/munge:munge --mungearg /tmp/input
   * </pre>
   */
  @Argument
  private List<String> noDashArguments = Lists.newArrayList();

  @Option(name = "--", handler = ConsumeAllOptionsHandler.class)
  private List<String> withDashArguments = Lists.newArrayList();

  private Supplier<ImmutableList<String>> arguments = Suppliers.memoize(
    new Supplier<ImmutableList<String>>() {
      @Override
      public ImmutableList<String> get() {
        ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
        builder.addAll(noDashArguments);
        builder.addAll(withDashArguments);
        return builder.build();
      }
    });

  public ImmutableList<String> getArguments() { return arguments.get(); }

  /** @return the arguments (if any) to be passed to the target command. */
  public ImmutableList<String> getTargetArguments() {
    return arguments.get().subList(1, arguments.get().size());
  }

  public boolean hasTargetSpecified() {
    return arguments.get().size() > 0;
  }

  /** @return the normalized target name for command to run. */
  public String getTarget(BuckConfig buckConfig) {
      return getCommandLineBuildTargetNormalizer(buckConfig).normalize(arguments.get().get(0));
  }

  @VisibleForTesting
  void setArguments(ImmutableList<String> arguments) {
    this.arguments = Suppliers.ofInstance(arguments);
  }

  @Override
  public String getShortDescription() {
    return "runs a target as a command";
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (!hasTargetSpecified()) {
      params.getConsole().printBuildFailure("No target given to run");
      params.getConsole().getStdOut().println("buck run <target> <arg1> <arg2>...");
      return 1;
    }

    // Make sure the target is built.
    BuildCommand buildCommand = new BuildCommand();
    buildCommand.setArguments(ImmutableList.of(getTarget(params.getBuckConfig())));
    int exitCode = buildCommand.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    String targetName = getTarget(params.getBuckConfig());
    BuildTarget target = Iterables.getOnlyElement(getBuildTargets(ImmutableSet.of(targetName)));

    Build build = buildCommand.getBuild();
    BuildRule targetRule = build.getActionGraph().findBuildRuleByTarget(target);
    BinaryBuildRule binaryBuildRule = null;
    if (targetRule instanceof BinaryBuildRule) {
      binaryBuildRule = (BinaryBuildRule) targetRule;
    }
    if (binaryBuildRule == null) {
      params.getConsole().printBuildFailure(
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
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(ImmutableSet.copyOf(build.getActionGraph().getNodes())));
    ImmutableList<String> fullCommand = new ImmutableList.Builder<String>()
        .addAll(binaryBuildRule.getExecutableCommand().getCommandPrefix(resolver))
        .addAll(getTargetArguments())
        .build();

    ShellStep step = new DefaultShellStep(
        params.getRepository().getFilesystem().getRootPath(),
        fullCommand) {
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
  public boolean isReadOnly() {
    return false;
  }

}
