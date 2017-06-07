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
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public final class RunCommand extends AbstractCommand {

  /**
   * Expected usage:
   *
   * <pre>
   *   buck run //java/com/facebook/tools/munge:munge --mungearg /tmp/input
   * </pre>
   */
  @Argument private List<String> noDashArguments = new ArrayList<>();

  @Option(name = "--", handler = ConsumeAllOptionsHandler.class)
  private List<String> withDashArguments = new ArrayList<>();

  private final Supplier<ImmutableList<String>> arguments =
      Suppliers.memoize(
          () -> {
            ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
            builder.addAll(noDashArguments);
            builder.addAll(withDashArguments);
            return builder.build();
          });

  @VisibleForTesting
  ImmutableList<String> getArguments() {
    return arguments.get();
  }

  /** @return the arguments (if any) to be passed to the target command. */
  private ImmutableList<String> getTargetArguments() {
    return arguments.get().subList(1, arguments.get().size());
  }

  private boolean hasTargetSpecified() {
    return arguments.get().size() > 0;
  }

  /** @return the normalized target name for command to run. */
  private String getTarget(BuckConfig buckConfig) {
    return Iterables.getOnlyElement(
        getCommandLineBuildTargetNormalizer(buckConfig).normalize(arguments.get().get(0)));
  }

  @Override
  public String getShortDescription() {
    return "runs a target as a command";
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (!hasTargetSpecified()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("No target given to run"));
      params.getBuckEventBus().post(ConsoleEvent.severe("buck run <target> <arg1> <arg2>..."));
      return 1;
    }

    // Make sure the target is built.
    BuildCommand buildCommand =
        new BuildCommand(ImmutableList.of(getTarget(params.getBuckConfig())));
    int exitCode = buildCommand.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    String targetName = getTarget(params.getBuckConfig());
    BuildTarget target =
        Iterables.getOnlyElement(
            getBuildTargets(params.getCell().getCellPathResolver(), ImmutableSet.of(targetName)));

    Build build = buildCommand.getBuild();
    BuildRule targetRule;
    try {
      targetRule = build.getRuleResolver().requireRule(target);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e.getHumanReadableErrorMessage());
    }
    BinaryBuildRule binaryBuildRule = null;
    if (targetRule instanceof BinaryBuildRule) {
      binaryBuildRule = (BinaryBuildRule) targetRule;
    }
    if (binaryBuildRule == null) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  "target "
                      + targetName
                      + " is not a binary rule (only binary rules can be `run`)"));
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
        new SourcePathResolver(new SourcePathRuleFinder(build.getRuleResolver()));
    Tool executable = binaryBuildRule.getExecutableCommand();
    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(executable.getCommandPrefix(resolver))
            .addAllCommand(getTargetArguments())
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(params.getEnvironment())
                    .putAll(executable.getEnvironment(resolver))
                    .build())
            .setDirectory(params.getCell().getFilesystem().getRootPath())
            .build();
    ForwardingProcessListener processListener =
        new ForwardingProcessListener(
            Channels.newChannel(params.getConsole().getStdOut()),
            Channels.newChannel(params.getConsole().getStdErr()));
    ListeningProcessExecutor.LaunchedProcess process =
        processExecutor.launchProcess(processExecutorParams, processListener);
    try {
      return processExecutor.waitForProcess(process);
    } finally {
      processExecutor.destroyProcess(process, /* force */ false);
      processExecutor.waitForProcess(process);
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }
}
