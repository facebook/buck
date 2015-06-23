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
import com.facebook.buck.rules.TargetNodes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.Collection;
import java.util.List;


public class AuditDependenciesCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(AuditDependenciesCommand.class);

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

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
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

    ImmutableSet<BuildTarget> targets = FluentIterable
        .from(getArgumentsFormattedAsBuildTargets(params.getBuckConfig()))
        .transform(
            new Function<String, BuildTarget>() {
              @Override
              public BuildTarget apply(String input) {
                return params.getParser().getBuildTargetParser().parse(
                    input,
                    BuildTargetPatternParser.fullyQualified(
                        params.getParser().getBuildTargetParser()));
              }
            })
        .toSet();

    TargetGraph graph;
    try {
      graph = params.getParser().buildTargetGraphForBuildTargets(
          targets,
          new ParserConfig(params.getBuckConfig()),
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    TreeMultimap<BuildTarget, BuildTarget> targetsAndDependencies = TreeMultimap.create();
    for (BuildTarget target : targets) {
      targetsAndDependencies.putAll(target, getDependenciesWithOptions(params, target, graph));
    }

    if (shouldGenerateJsonOutput()) {
      printJSON(params, targetsAndDependencies);
    } else {
      printToConsole(params, targetsAndDependencies);
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  ImmutableSet<BuildTarget> getDependenciesWithOptions(
      CommandRunnerParams params,
      BuildTarget target,
      TargetGraph graph) throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targetsToPrint = shouldShowTransitiveDependencies() ?
        getTransitiveDependencies(ImmutableSet.of(target), graph) :
        getImmediateDependencies(target, graph);

    if (shouldIncludeTests()) {
      ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
      targetsToPrint = builder
          .addAll(targetsToPrint)
          .addAll(getTestTargetDependencies(params, target, graph))
          .build();
    }
    return targetsToPrint;
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
  ImmutableSet<BuildTarget> getImmediateDependencies(BuildTarget target, TargetGraph graph) {
    return Preconditions.checkNotNull(graph.get(target)).getDeps();
  }

  @VisibleForTesting
  Collection<BuildTarget> getTestTargetDependencies(
      CommandRunnerParams params,
      BuildTarget target,
      TargetGraph graph) throws IOException, InterruptedException {
    if (!shouldShowTransitiveDependencies()) {
      return TargetNodes.getTestTargetsForNode(Preconditions.checkNotNull(graph.get(target)));
    }

    ProjectGraphParser projectGraphParser = ProjectGraphParsers.createProjectGraphParser(
        params.getParser(),
        new ParserConfig(params.getBuckConfig()),
        params.getBuckEventBus(),
        params.getConsole(),
        params.getEnvironment(),
        getEnableProfiling());

    TargetGraph graphWithTests = TargetGraphTestParsing.expandedTargetGraphToIncludeTestsForTargets(
        projectGraphParser,
        graph,
        ImmutableSet.of(target));

    ImmutableSet<BuildTarget> tests = TargetGraphAndTargets.getExplicitTestTargets(
        ImmutableSet.of(target),
        graphWithTests);
    // We want to return the set of all tests plus their dependencies. Luckily
    // `getTransitiveDependencies` will give us the last part, but we need to make sure we include
    // the tests themselves in our final output
    Sets.SetView<BuildTarget> testsWithDependencies = Sets.union(
        tests,
        getTransitiveDependencies(tests, graphWithTests));
    // Tests normally depend on the code they are testing, but we don't want to include that in our
    // output, so explicitly filter that here.
    return Sets.difference(testsWithDependencies, ImmutableSet.of(target));
  }

  private void printJSON(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndDependencies) throws IOException {
    Multimap<BuildTarget, String> targetsAndDependenciesNames =
        Multimaps.transformValues(
            targetsAndDependencies, new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return Preconditions.checkNotNull(input.getFullyQualifiedName());
              }
            });
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsAndDependenciesNames.asMap());
  }

  private void printToConsole(
      CommandRunnerParams params,
      Multimap<BuildTarget, BuildTarget> targetsAndDependencies) {
    for (BuildTarget target : targetsAndDependencies.values()) {
      params.getConsole().getStdOut().println(target.getFullyQualifiedName());
    }
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' dependencies";
  }

}
