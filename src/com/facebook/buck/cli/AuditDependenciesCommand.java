/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.query.QueryException;
import com.facebook.buck.event.ConsoleEvent;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.List;

public class AuditDependenciesCommand extends AbstractCommand {

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Option(
      name = "--include-tests",
      usage = "Includes a target's tests with its dependencies. With the transitive flag, this " +
          "prints the dependencies of the tests as well")
  private boolean includeTests = false;

  @Option(name = "--transitive",
      aliases = { "-t" },
      usage = "Whether to include transitive dependencies in the output")
  private boolean transitive = false;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  public boolean shouldShowTransitiveDependencies() {
    return transitive;
  }

  public boolean shouldIncludeTests() {
    return includeTests;
  }

  public List<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public int runWithoutHelp(final CommandRunnerParams params)
      throws IOException, InterruptedException {
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig()));

    if (fullyQualifiedBuildTargets.isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      params.getBuckEventBus().post(ConsoleEvent.info(
          "'buck audit dependencies' is deprecated. Please use 'buck query' instead.\n" +
          "The equivalent 'buck query' command is:\n$ %s\n\nThe query language is documented at " +
          "https://buckbuild.com/command/query.html",
          QueryCommand.buildAuditDependenciesQueryExpression(
              getArguments(),
              shouldShowTransitiveDependencies(),
              shouldIncludeTests(),
              shouldGenerateJsonOutput())));
    }

    BuckQueryEnvironment env = new BuckQueryEnvironment(params, getEnableProfiling());
    try {
      return QueryCommand.runMultipleQuery(
          params,
          env,
          QueryCommand.getAuditDependenciesQueryFormat(
              shouldShowTransitiveDependencies(),
              shouldIncludeTests()),
          getArgumentsFormattedAsBuildTargets(params.getBuckConfig()),
          shouldGenerateJsonOutput());
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' dependencies";
  }

}
