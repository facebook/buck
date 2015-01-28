/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphHashing;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.annotation.Nullable;

public class TargetsCommand extends AbstractCommandRunner<TargetsCommandOptions> {

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public TargetsCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  TargetsCommandOptions createOptions(BuckConfig buckConfig) {
    return new TargetsCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(TargetsCommandOptions options)
      throws IOException, InterruptedException {
    // Exit early if --resolvealias is passed in: no need to parse any build files.
    if (options.isResolveAlias()) {
      return doResolveAlias(options);
    }

    if (options.isShowRuleKey() && options.isShowTargetHash()) {
      throw new HumanReadableException("Cannot show rule key and target hash at the same time.");
    }

    if (options.isShowOutput() || options.isShowRuleKey() || options.isShowTargetHash()) {
      return doShowRules(options);
    }

    // Verify the --type argument.
    ImmutableSet<String> types = options.getTypes();
    ImmutableSet.Builder<BuildRuleType> buildRuleTypesBuilder = ImmutableSet.builder();
    for (String name : types) {
      try {
        buildRuleTypesBuilder.add(getRepository().getBuildRuleType(name));
      } catch (IllegalArgumentException e) {
        console.printBuildFailure("Invalid build rule type: " + name);
        return 1;
      }
    }

    ImmutableSet<BuildTarget> matchingBuildTargets = ImmutableSet.copyOf(
        getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));

    // Parse the entire action graph, or (if targets are specified),
    // only the specified targets and their dependencies..
    //
    // TODO(jakubzika):
    // If --detect-test-changes is specified, we need to load the whole graph, because we cannot
    // know which targets can refer to the specified targets or their dependencies in their
    // 'source_under_test'. Once we migrate from 'source_under_test' to 'tests', this should no
    // longer be necessary.
    ParserConfig parserConfig = new ParserConfig(options.getBuckConfig());
    TargetGraph graph;
    try {
      if (matchingBuildTargets.isEmpty() || options.isDetectTestChanges()) {
        graph = getParser().buildTargetGraphForTargetNodeSpecs(
            ImmutableList.of(
                new TargetNodePredicateSpec(
                    Predicates.<TargetNode<?>>alwaysTrue(),
                    getProjectFilesystem().getIgnorePaths())),
            parserConfig,
            getBuckEventBus(),
            console,
            environment,
            options.getEnableProfiling());
      } else {
        graph = getParser().buildTargetGraphForBuildTargets(
            matchingBuildTargets,
            parserConfig,
            getBuckEventBus(),
            console,
            environment,
            options.getEnableProfiling());
      }
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    PathArguments.ReferencedFiles referencedFiles = options.getReferencedFiles(
        getProjectFilesystem().getRootPath());
    SortedMap<String, TargetNode<?>> matchingNodes;
    // If all of the referenced files are paths outside the project root, then print nothing.
    if (!referencedFiles.absolutePathsOutsideProjectRootOrNonExistingPaths.isEmpty() &&
        referencedFiles.relativePathsUnderProjectRoot.isEmpty()) {
      matchingNodes = ImmutableSortedMap.of();
    } else {
      ImmutableSet<BuildRuleType> buildRuleTypes = buildRuleTypesBuilder.build();

      matchingNodes = getMatchingNodes(
          graph,
          referencedFiles.relativePathsUnderProjectRoot.isEmpty() ?
              Optional.<ImmutableSet<Path>>absent() :
              Optional.of(referencedFiles.relativePathsUnderProjectRoot),
          matchingBuildTargets.isEmpty() ?
              Optional.<ImmutableSet<BuildTarget>>absent() :
              Optional.of(matchingBuildTargets),
          buildRuleTypes.isEmpty() ?
              Optional.<ImmutableSet<BuildRuleType>>absent() :
              Optional.of(buildRuleTypes),
          options.isDetectTestChanges(),
          parserConfig.getBuildFileName());
    }

    // Print out matching targets in alphabetical order.
    if (options.getPrintJson()) {
      try {
        printJsonForTargets(matchingNodes, new ParserConfig(options.getBuckConfig()));
      } catch (BuildFileParseException e) {
        console.printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    } else {
      for (String target : matchingNodes.keySet()) {
        getStdOut().println(target);
      }
    }

    return 0;
  }

  /**
   * @param graph Graph used to resolve dependencies between targets and find all build files.
   * @param referencedFiles If present, the result will be limited to the nodes that transitively
   *                        depend on at least one of those.
   * @param matchingBuildTargets If present, the result will be limited to the specified targets.
   * @param buildRuleTypes If present, the result will be limited to targets with the specified
   *                       types.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *                          are testing.
   * @return A map of target names to target nodes.
   */
  @VisibleForTesting
  ImmutableSortedMap<String, TargetNode<?>> getMatchingNodes(
      TargetGraph graph,
      Optional<ImmutableSet<Path>> referencedFiles,
      final Optional<ImmutableSet<BuildTarget>> matchingBuildTargets,
      final Optional<ImmutableSet<BuildRuleType>> buildRuleTypes,
      boolean detectTestChanges,
      String buildFileName) {
    ImmutableSet<TargetNode<?>> directOwners;
    if (referencedFiles.isPresent()) {
      BuildFileTree buildFileTree = new InMemoryBuildFileTree(
            FluentIterable
                .from(graph.getNodes())
                .transform(HasBuildTarget.TO_TARGET)
                .toSet());
      directOwners = FluentIterable
          .from(graph.getNodes())
          .filter(
              new DirectOwnerPredicate(
                  buildFileTree,
                  referencedFiles.get(),
                  buildFileName))
          .toSet();
    } else {
      directOwners = graph.getNodes();
    }
    ImmutableSet<TargetNode<?>> selectedReferrers = FluentIterable
        .from(getDependentNodes(graph, directOwners, detectTestChanges))
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> targetNode) {
                if (matchingBuildTargets.isPresent() &&
                    !matchingBuildTargets.get().contains(targetNode.getBuildTarget())) {
                  return false;
                }

                if (buildRuleTypes.isPresent() &&
                    !buildRuleTypes.get().contains(targetNode.getType())) {
                  return false;
                }

                return true;
              }
            })
        .toSet();
    ImmutableSortedMap.Builder<String, TargetNode<?>> matchingNodesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (TargetNode<?> targetNode : selectedReferrers) {
      matchingNodesBuilder.put(targetNode.getBuildTarget().getFullyQualifiedName(), targetNode);
    }
    return matchingNodesBuilder.build();
  }

  /**
   * @param graph A graph used to resolve dependencies between targets.
   * @param nodes A set of nodes.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *                          are testing.
   * @return A set of all nodes that transitively depend on {@code nodes}
   * (a superset of {@code nodes}).
   */
  private static ImmutableSet<TargetNode<?>> getDependentNodes(
      final TargetGraph graph,
      ImmutableSet<TargetNode<?>> nodes,
      boolean detectTestChanges) {
    ImmutableMultimap.Builder<TargetNode<?>, TargetNode<?>> extraEdgesBuilder =
        ImmutableMultimap.builder();

    if (detectTestChanges) {
      for (TargetNode<?> node : graph.getNodes()) {
        if (node.getConstructorArg() instanceof HasTests) {
          ImmutableSortedSet<BuildTarget> tests =
              ((HasTests) node.getConstructorArg()).getTests();
          for (BuildTarget testTarget : tests) {
            TargetNode<?> testNode = graph.get(testTarget);
            if (testNode == null) {
              throw new HumanReadableException(
                  "'%s' (test of '%s') is not in the target graph.",
                  testTarget,
                  node);
            }
            extraEdgesBuilder.put(testNode, node);
          }
        }

        if (node.getConstructorArg() instanceof HasSourceUnderTest) {
          ImmutableSortedSet<BuildTarget> sourceUnderTest =
              ((HasSourceUnderTest) node.getConstructorArg()).getSourceUnderTest();
          for (BuildTarget sourceTarget : sourceUnderTest) {
            TargetNode<?> sourceNode = Preconditions.checkNotNull(graph.get(sourceTarget));
            extraEdgesBuilder.put(node, sourceNode);
          }
        }
      }
    }
    final ImmutableMultimap<TargetNode<?>, TargetNode<?>> extraEdges = extraEdgesBuilder.build();

    final ImmutableSet.Builder<TargetNode<?>> builder = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<TargetNode<?>> traversal =
        new AbstractBreadthFirstTraversal<TargetNode<?>>(nodes) {
          @Override
          public ImmutableSet<TargetNode<?>> visit(TargetNode<?> targetNode) {
            builder.add(targetNode);
            return FluentIterable
                .from(graph.getIncomingNodesFor(targetNode))
                .append(extraEdges.get(targetNode))
                .toSet();
          }
    };
    traversal.start();
    return builder.build();
  }

  @Override
  String getUsageIntro() {
    return "prints the list of buildable targets";
  }

  @VisibleForTesting
  void printJsonForTargets(
      SortedMap<String, TargetNode<?>> buildIndex,
      ParserConfig parserConfig)
      throws BuildFileParseException, IOException, InterruptedException {
    // Print the JSON representation of the build node for the specified target(s).
    getStdOut().println("[");

    ObjectMapper mapper = getObjectMapper();
    Iterator<TargetNode<?>> valueIterator = buildIndex.values().iterator();

    while (valueIterator.hasNext()) {
      BuildTarget buildTarget = valueIterator.next().getBuildTarget();

      List<Map<String, Object>> rules;
      try {
        Path buildFile = getRepository().getAbsolutePathToBuildFile(buildTarget);
        rules = getParser().parseBuildFile(
            buildFile,
            parserConfig,
            environment,
            console,
            getBuckEventBus());
      } catch (BuildTargetException e) {
        console.printErrorText(
            "unable to find rule for target " + buildTarget.getFullyQualifiedName());
        continue;
      }

      // Find the build rule information that corresponds to this build buildTarget.
      Map<String, Object> targetRule = null;
      for (Map<String, Object> rule : rules) {
        String name = (String) Preconditions.checkNotNull(rule.get("name"));
        if (name.equals(buildTarget.getShortNameAndFlavorPostfix())) {
          targetRule = rule;
          break;
        }
      }

      if (targetRule == null) {
        console.printErrorText(
            "unable to find rule for target " + buildTarget.getFullyQualifiedName());
        continue;
      }

      // Sort the rule items, both so we have a stable order for unit tests and
      // to improve readability of the output.
      SortedMap<String, Object> sortedTargetRule = Maps.newTreeMap();
      sortedTargetRule.putAll(targetRule);

      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      try {
        mapper.writerWithDefaultPrettyPrinter().writeValue(stringWriter, sortedTargetRule);
      } catch (IOException e) {
        // Shouldn't be possible while writing to a StringWriter...
        throw Throwables.propagate(e);
      }
      String output = stringWriter.getBuffer().toString();
      if (valueIterator.hasNext()) {
        output += ",";
      }
      getStdOut().println(output);
    }

    getStdOut().println("]");
  }

  /**
   * Assumes each argument passed to this command is an alias defined in .buckconfig,
   * or a fully qualified (non-alias) target to be verified by checking the build files.
   * Prints the build target that each alias maps to on its own line to standard out.
   */
  private int doResolveAlias(TargetsCommandOptions options)
      throws IOException, InterruptedException {
    List<String> resolvedAliases = Lists.newArrayList();
    for (String alias : options.getArguments()) {
      String buildTarget;
      if (alias.startsWith("//")) {
        buildTarget = validateBuildTargetForFullyQualifiedTarget(alias, options, getParser());
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not a valid target.", alias);
        }
      } else {
        buildTarget = options.getBuildTargetForAlias(alias);
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not an alias.", alias);
        }
      }
      resolvedAliases.add(buildTarget);
    }

    for (String resolvedAlias : resolvedAliases) {
      getStdOut().println(resolvedAlias);
    }

    return 0;
  }

  /**
   * Assumes at least one target is specified.  Prints each of the
   * specified targets, followed by the rule key, output path, and/or
   * target hash, depending on what flags are passed in.
   */
  private int doShowRules(TargetsCommandOptions options) throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> matchingBuildTargets = ImmutableSet.copyOf(
        getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));

    if (matchingBuildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    TargetGraph targetGraph;
    try {
      targetGraph = getParser().buildTargetGraphForBuildTargets(
          matchingBuildTargets,
          new ParserConfig(options.getBuckConfig()),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    Optional<ActionGraph> actionGraph;
    if (options.isShowRuleKey() || options.isShowOutput()) {
      actionGraph = Optional.of(targetGraphTransformer.apply(targetGraph));
    } else {
      actionGraph = Optional.absent();
    }

    ImmutableMap<BuildTarget, HashCode> buildTargetHashes;
    if (options.isShowTargetHash()) {
      buildTargetHashes = TargetGraphHashing.hashTargetGraph(
          getProjectFilesystem(),
          targetGraph,
          getParser().getBuildTargetHashCodeCache(),
          matchingBuildTargets);
    } else {
      buildTargetHashes = ImmutableMap.of();
    }

    for (BuildTarget target : ImmutableSortedSet.copyOf(matchingBuildTargets)) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(target.getFullyQualifiedName());
      if (options.isShowTargetHash()) {
        HashCode targetHash = Preconditions.checkNotNull(buildTargetHashes.get(target));
        builder.add(targetHash.toString());
      }
      if (actionGraph.isPresent()) {
        BuildRule rule = Preconditions.checkNotNull(
            actionGraph.get().findBuildRuleByTarget(target));
        if (options.isShowRuleKey()) {
          builder.add(rule.getRuleKey().toString());
        }
        if (options.isShowOutput()) {
          Path outputPath = rule.getPathToOutputFile();
          if (outputPath != null) {
            builder.add(outputPath.toString());
          }
        }
      }
      getStdOut().println(Joiner.on(' ').join(builder.build()));
    }

    return 0;
  }

  /**
   * Verify that the given target is a valid full-qualified (non-alias) target.
   */
  @Nullable
  @VisibleForTesting
  String validateBuildTargetForFullyQualifiedTarget(
      String target,
      TargetsCommandOptions options,
      Parser parser) throws IOException, InterruptedException {
    BuildTarget buildTarget;
    try {
      buildTarget = options.getBuildTargetForFullyQualifiedTarget(target);
    } catch (NoSuchBuildTargetException e) {
      return null;
    }

    // Get all valid targets in our target directory by reading the build file.
    List<Map<String, Object>> ruleObjects;
    try {
      ruleObjects = parser.parseBuildFile(
          getRepository().getAbsolutePathToBuildFile(buildTarget),
          new ParserConfig(options.getBuckConfig()),
          environment,
          console,
          getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      // TODO(devjasta): this doesn't smell right!
      return null;
    }

    // Check that the given target is a valid target.
    for (Map<String, Object> rule : ruleObjects) {
      String name = (String) Preconditions.checkNotNull(rule.get("name"));
      if (name.equals(buildTarget.getShortNameAndFlavorPostfix())) {
        return buildTarget.getFullyQualifiedName();
      }
    }
    return null;
  }

  private static class DirectOwnerPredicate implements Predicate<TargetNode<?>> {

    private final ImmutableSet<Path> referencedInputs;
    private final ImmutableSet<Path> basePathOfTargets;
    private final String buildFileName;

    /**
     * @param referencedInputs A {@link TargetNode} must reference at least one of these paths as
     *     input to match the predicate. All the paths must be relative to the project root. Ignored
     *     if empty.
     */
    public DirectOwnerPredicate(
        BuildFileTree buildFileTree,
        ImmutableSet<Path> referencedInputs,
        String buildFileName) {
      this.referencedInputs = referencedInputs;

      ImmutableSet.Builder<Path> basePathOfTargetsBuilder = ImmutableSet.builder();
      for (Path input : referencedInputs) {
        Optional<Path> path = buildFileTree.getBasePathOfAncestorTarget(input);
        if (path.isPresent()) {
          basePathOfTargetsBuilder.add(path.get());
        }
      }
      this.basePathOfTargets = basePathOfTargetsBuilder.build();
      this.buildFileName = buildFileName;
    }

    @Override
    public boolean apply(TargetNode<?> node) {
      // For any referenced file, only those with the nearest target base path can
      // directly depend on that file.
      if (!basePathOfTargets.contains(node.getBuildTarget().getBasePath())) {
        return false;
      }

      for (Path input : node.getInputs()) {
        for (Path referencedInput : referencedInputs) {
          if (referencedInput.startsWith(input)) {
            return true;
          }
        }
      }

      return referencedInputs.contains(node.getBuildTarget().getBasePath().resolve(buildFileName));
    }
  }
}
