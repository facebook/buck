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
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.util.Collection;


public class AuditTestsCommand extends AbstractCommandRunner<AuditTestsOptions> {

  private static final Logger LOG = Logger.get(AuditTestsCommand.class);

  public AuditTestsCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  AuditTestsOptions createOptions(BuckConfig buckConfig) {
    return new AuditTestsOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditTestsOptions options)
      throws IOException, InterruptedException {
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> targets =
        getBuildTargets(ImmutableSet.copyOf(options.getArgumentsFormattedAsBuildTargets()));

    TargetGraph graph;
    try {
      graph = getParser().buildTargetGraphForBuildTargets(
          targets,
          new ParserConfig(options.getBuckConfig()),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    ImmutableSet<BuildTarget> targetsToPrint = getTestsForTargets(targets, graph);
    LOG.debug("Printing out the following targets: " + targetsToPrint);

    Collection<String> namesToPrint = Collections2.transform(
        targetsToPrint, new Function<BuildTarget, String>() {

          @Override
          public String apply(final BuildTarget target) {
            return target.getFullyQualifiedName();
          }
        });

    ImmutableSortedSet<String> sortedNames = ImmutableSortedSet.copyOf(namesToPrint);

    if (options.shouldGenerateJsonOutput()) {
      printJSON(sortedNames);
    } else {
      printToConsole(sortedNames);
    }

    return 0;
  }

  ImmutableSet<BuildTarget> getTestsForTargets(
      final ImmutableSet<BuildTarget> targets,
      TargetGraph graph) {
    return TargetGraphAndTargets.getExplicitTestTargets(graph.getAll(targets));
  }

  private void printJSON(Collection<String> names) throws IOException {
    getObjectMapper().writeValue(
        console.getStdOut(),
        names);
  }

  private void printToConsole(Collection<String> names) {
    for (String name : names) {
      getStdOut().println(name);
    }
  }

  @Override
  String getUsageIntro() {
    return "provides facilities to audit build targets' tests";
  }

}
