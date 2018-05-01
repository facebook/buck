/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.shell.WorkerTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Worker macro wrapper which extracts/verifies details from the an underlying {@link WorkerTool}.
 */
public class WorkerMacroArg extends ProxyArg {

  private final BuildTarget workerTarget;
  private final WorkerTool workerTool;
  private final ImmutableList<String> startupCommand;
  private final ImmutableMap<String, String> startupEnvironment;

  private WorkerMacroArg(
      Arg arg,
      BuildTarget workerTarget,
      WorkerTool workerTool,
      ImmutableList<String> startupCommand,
      ImmutableMap<String, String> startupEnvironment) {
    super(arg);
    this.workerTarget = workerTarget;
    this.workerTool = workerTool;
    this.startupCommand = startupCommand;
    this.startupEnvironment = startupEnvironment;
  }

  /** @return a {@link WorkerMacroArg} which wraps the given {@link MacroArg}. */
  public static WorkerMacroArg fromMacroArg(
      MacroArg arg,
      MacroHandler macroHandler,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String unexpanded)
      throws MacroException {

    for (MacroMatchResult matchResult : macroHandler.getMacroMatchResults(unexpanded)) {
      if (macroHandler.getExpander(matchResult.getMacroType()) instanceof WorkerMacroExpander
          && matchResult.getStartIndex() != 0) {
        throw new MacroException(
            String.format("the worker macro in \"%s\" must be at the beginning", unexpanded));
      }
    }

    // extract the BuildTargets referenced in any macros
    ImmutableList.Builder<BuildTarget> targetsBuilder = new ImmutableList.Builder<>();
    macroHandler.extractParseTimeDeps(
        target, cellNames, unexpanded, targetsBuilder, new ImmutableSet.Builder<>());
    ImmutableList<BuildTarget> targets = targetsBuilder.build();

    if (targets.isEmpty()) {
      throw new MacroException(
          String.format(
              "Unable to extract any build targets for the macros " + "used in \"%s\" of target %s",
              unexpanded, target));
    }
    BuildTarget workerTarget = targets.get(0);
    BuildRule workerRule = resolver.getRule(workerTarget);
    if (!(workerRule instanceof WorkerTool)) {
      throw new MacroException(
          String.format(
              "%s used in worker macro, \"%s\", of target %s does "
                  + "not correspond to a worker_tool",
              workerTarget, unexpanded, target));
    }
    WorkerTool workerTool = (WorkerTool) workerRule;
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Tool exe = workerTool.getTool();
    ImmutableList<String> startupCommand = exe.getCommandPrefix(pathResolver);
    ImmutableMap<String, String> startupEnvironment = exe.getEnvironment(pathResolver);
    return new WorkerMacroArg(arg, workerTarget, workerTool, startupCommand, startupEnvironment);
  }

  /** @return a {@link WorkerMacroArg} which wraps the given {@link StringWithMacros}. */
  public static WorkerMacroArg fromStringWithMacros(
      Arg arg, BuildTarget target, BuildRuleResolver resolver, StringWithMacros unexpanded) {
    if (unexpanded.getMacros().isEmpty()) {
      throw new HumanReadableException(
          String.format("%s: no macros in \"%s\"", target, unexpanded));
    }
    Macro firstMacro = unexpanded.getMacros().get(0).getMacro();
    if (!(firstMacro instanceof WorkerMacro)) {
      throw new HumanReadableException(
          String.format(
              "%s: the worker macro in \"%s\" must be at the beginning", target, unexpanded));
    }
    WorkerMacro workerMacro = (WorkerMacro) firstMacro;

    BuildTarget workerTarget = workerMacro.getTarget();
    BuildRule workerRule = resolver.getRule(workerTarget);
    if (!(workerRule instanceof WorkerTool)) {
      throw new HumanReadableException(
          String.format(
              "%s used in worker macro, \"%s\", of target %s does "
                  + "not correspond to a worker_tool",
              workerTarget, unexpanded, target));
    }
    WorkerTool workerTool = (WorkerTool) workerRule;
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Tool exe = workerTool.getTool();
    ImmutableList<String> startupCommand = exe.getCommandPrefix(pathResolver);
    ImmutableMap<String, String> startupEnvironment = exe.getEnvironment(pathResolver);
    return new WorkerMacroArg(arg, workerTarget, workerTool, startupCommand, startupEnvironment);
  }

  public ImmutableList<String> getStartupCommand() {
    return startupCommand;
  }

  public ImmutableMap<String, String> getEnvironment() {
    return startupEnvironment;
  }

  public Path getTempDir() {
    return workerTool.getTempDir();
  }

  public Optional<String> getPersistentWorkerKey() {
    if (workerTool.isPersistent()) {
      return Optional.of(workerTarget.getCellPath().toString() + workerTarget);
    } else {
      return Optional.empty();
    }
  }

  public HashCode getWorkerHash() {
    return workerTool.getInstanceKey();
  }

  public int getMaxWorkers() {
    return workerTool.getMaxWorkers();
  }

  public String getJobArgs(SourcePathResolver pathResolver) {
    return Arg.stringify(arg, pathResolver).trim();
  }
}
