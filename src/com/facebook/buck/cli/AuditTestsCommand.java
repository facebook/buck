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
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.TreeMultimap;

import java.io.IOException;


public class AuditTestsCommand extends AbstractCommandRunner<AuditCommandOptions> {

  private static final Logger LOG = Logger.get(AuditTestsCommand.class);

  @Override
  AuditCommandOptions createOptions(BuckConfig buckConfig) {
    return new AuditCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(CommandRunnerParams params, AuditCommandOptions options)
      throws IOException, InterruptedException {
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> targets =
        getBuildTargets(params, ImmutableSet.copyOf(options.getArgumentsFormattedAsBuildTargets()));

    TargetGraph graph;
    try {
      graph = params.getParser().buildTargetGraphForBuildTargets(
          targets,
          new ParserConfig(options.getBuckConfig()),
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          options.getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    TreeMultimap<BuildTarget, BuildTarget> targetsToPrint =
        getTestsForTargets(targets, graph);
    LOG.debug("Printing out the following targets: " + targetsToPrint);

    if (options.shouldGenerateJsonOutput()) {
      printJSON(params, targetsToPrint);
    } else {
      printToConsole(params, targetsToPrint);
    }

    return 0;
  }

  TreeMultimap<BuildTarget, BuildTarget> getTestsForTargets(
      final ImmutableSet<BuildTarget> targets,
      final TargetGraph graph) {
    TreeMultimap<BuildTarget, BuildTarget> multimap = TreeMultimap.create();
    for (BuildTarget target : targets) {
      multimap.putAll(target, TargetNodes.getTestTargetsForNode(graph.get(target)));
    }
    return multimap;
  }

  private void printJSON(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndTests)
      throws IOException {
    Multimap<BuildTarget, String> targetsAndTestNames =
        Multimaps.transformValues(targetsAndTests, new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return Preconditions.checkNotNull(input.getFullyQualifiedName());
              }
            });
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsAndTestNames.asMap());
  }

  private void printToConsole(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndTests) {
    for (BuildTarget target : targetsAndTests.values()) {
      params.getConsole().getStdOut().println(target.getFullyQualifiedName());
    }
  }

  @Override
  String getUsageIntro() {
    return "provides facilities to audit build targets' tests";
  }

}
