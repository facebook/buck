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

package com.facebook.buck.rules.args;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroMatchResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.shell.WorkerTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Optional;

public class WorkerMacroArg extends MacroArg {

  private final WorkerTool workerTool;
  private final ImmutableList<String> startupCommand;
  private final ImmutableMap<String, String> startupEnvironment;
  private final String jobArgs;
  private final BuildTarget buildTarget;

  public WorkerMacroArg(
      MacroHandler macroHandler,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String unexpanded) throws MacroException {
    super(macroHandler, target, cellNames, resolver, unexpanded);
    for (MacroMatchResult matchResult : macroHandler.getMacroMatchResults(unexpanded)) {
      if (macroHandler.getExpander(matchResult.getMacroType()) instanceof WorkerMacroExpander &&
          matchResult.getStartIndex() != 0) {
        throw new MacroException(String.format(
            "the worker macro in \"%s\" must be at the beginning",
            unexpanded));
      }
    }

    // extract the BuildTargets referenced in any macros
    ImmutableList<BuildTarget> targets = macroHandler.extractParseTimeDeps(
        target,
        cellNames,
        unexpanded);

    if (targets.size() < 1) {
      throw new MacroException(String.format("Unable to extract any build targets for the macros " +
          "used in \"%s\" of target %s",
          unexpanded,
          target));
    }
    this.buildTarget = targets.get(0);
    BuildRule workerTool = resolver.getRule(buildTarget);
    if (!(workerTool instanceof WorkerTool)) {
      throw new MacroException(String.format("%s used in worker macro, \"%s\", of target %s does " +
          "not correspond to a worker_tool",
          buildTarget,
          unexpanded,
          target));
    }
    this.workerTool = (WorkerTool) workerTool;
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Tool exe = this.workerTool.getTool();
    startupCommand = exe.getCommandPrefix(pathResolver);
    startupEnvironment = exe.getEnvironment();
    jobArgs = macroHandler.expand(target, cellNames, resolver, unexpanded).trim();
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

  public String getStartupArgs() {
    return workerTool.getArgs();
  }

  public Optional<String> getPersistentWorkerKey() {
    if (workerTool.isPersistent()) {
      return Optional.of(buildTarget.getCellPath().toString() + buildTarget.toString());
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

  public String getJobArgs() {
    return jobArgs;
  }
}
