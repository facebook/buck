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

import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;


public class AuditDependenciesCommand extends AbstractCommandRunner<AuditDependenciesOptions> {

  private static final Logger LOG = Logger.get(AuditDependenciesCommand.class);

  public AuditDependenciesCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  AuditDependenciesOptions createOptions(BuckConfig buckConfig) {
    return new AuditDependenciesOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditDependenciesOptions options)
      throws IOException, InterruptedException {
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> targets = FluentIterable
        .from(options.getArgumentsFormattedAsBuildTargets())
        .transform(
            new Function<String, BuildTarget>() {
              @Override
              public BuildTarget apply(String input) {
                return getParser().getBuildTargetParser().parse(
                    input,
                    BuildTargetPatternParser.fullyQualified(
                        getParser().getBuildTargetParser()));
              }
            })
        .toSet();

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

    ImmutableSet<BuildTarget> targetsToPrint = options.shouldShowTransitiveDependencies() ?
        getTransitiveDependencies(targets, graph) :
        getImmediateDependencies(targets, graph);

    if (options.shouldIncludeTests()) {
      ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();
      targetsToPrint = targetsBuilder
          .addAll(targetsToPrint)
          .addAll(getTestTargetDependencies(targets, graph, options))
          .build();
    }

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

  @VisibleForTesting
  ImmutableSet<BuildTarget> getTransitiveDependencies(
      final ImmutableSet<BuildTarget> targets,
      TargetGraph graph) {
    final ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();

    TargetGraph subgraph = graph.getSubgraph(graph.getAll(targets));
    new AbstractBottomUpTraversal<TargetNode<?>, Void>(subgraph) {

      @Override
      public void visit(TargetNode<?> node) {
        LOG.debug("Visiting dependency " + node.getBuildTarget().getFullyQualifiedName());
        // Don't add the requested target to the list of dependencies
        if (!targets.contains(node.getBuildTarget())) {
          builder.add(node.getBuildTarget());
        }
      }

      @Override
      public Void getResult() {
        return null;
      }

    }.traverse();

    return builder.build();
  }

  @VisibleForTesting
  ImmutableSet<BuildTarget> getImmediateDependencies(
      final ImmutableSet<BuildTarget> targets,
      TargetGraph graph) {
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();

    for (BuildTarget target : targets) {
      TargetNode<?> targetNode = graph.get(target);
      builder.addAll(targetNode.getDeps());
    }

    return builder.build();
  }

  @VisibleForTesting
  Collection<BuildTarget> getTestTargetDependencies(
      final ImmutableSet<BuildTarget> targets,
      TargetGraph graph,
      AuditDependenciesOptions options) throws IOException, InterruptedException {
    if (!options.shouldShowTransitiveDependencies()) {
      return TargetGraphAndTargets.getExplicitTestTargets(graph.getAll(targets));
    }

    ProjectGraphParser projectGraphParser = ProjectGraphParsers.createProjectGraphParser(
        getParser(),
        new ParserConfig(options.getBuckConfig()),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    TargetGraph graphWithTests = TargetGraphTestParsing.expandedTargetGraphToIncludeTestsForTargets(
        projectGraphParser,
        graph,
        targets);
    ImmutableSet<BuildTarget> tests = TargetGraphAndTargets.getExplicitTestTargets(
        targets,
        graphWithTests);
    // We want to return the set of all tests plus their dependencies. Luckily
    // `getTransitiveDependencies` will give us the last part, but we need to make sure we include
    // the tests themselves in our final output
    Set<BuildTarget> testsWithDependencies = Sets.union(
        tests,
        getTransitiveDependencies(tests, graphWithTests));
    // Tests normally depend on the code they are testing, but we don't want to include that in our
    // output, so explicitly filter that here.
    return Sets.difference(testsWithDependencies, targets);
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
    return "provides facilities to audit build targets' dependencies";
  }

}
