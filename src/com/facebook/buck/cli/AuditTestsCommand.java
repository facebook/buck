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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNodes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.TreeMultimap;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.List;

public class AuditTestsCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(AuditTestsCommand.class);

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig()));

    if (fullyQualifiedBuildTargets.isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> targets = getBuildTargets(
        ImmutableSet.copyOf(getArgumentsFormattedAsBuildTargets(params.getBuckConfig())));

    try {
      TreeMultimap<BuildTarget, BuildTarget> targetsToPrint =
          getTestsForTargets(params, targets, getEnableProfiling());
      LOG.debug("Printing out the following targets: " + targetsToPrint);

      if (shouldGenerateJsonOutput()) {
        CommandHelper.printJSON(params, targetsToPrint);
      } else {
        CommandHelper.printToConsole(params, targetsToPrint);
      }
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  public static TreeMultimap<BuildTarget, BuildTarget> getTestsForTargets(
      final CommandRunnerParams params,
      final ImmutableSet<BuildTarget> targets,
      boolean enableProfiling)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    TargetGraph graph = params.getParser().buildTargetGraphForBuildTargets(
        targets,
        new ParserConfig(params.getBuckConfig()),
        params.getBuckEventBus(),
        params.getConsole(),
        params.getEnvironment(),
        enableProfiling);

    TreeMultimap<BuildTarget, BuildTarget> multimap = TreeMultimap.create();
    for (BuildTarget target : targets) {
      multimap.putAll(
          target,
          TargetNodes.getTestTargetsForNode(Preconditions.checkNotNull(graph.get(target))));
    }
    return multimap;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' tests";
  }

}
