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

import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import javax.annotation.Nullable;

public class TargetsCommand extends AbstractCommandRunner<TargetsCommandOptions> {

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public TargetsCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(params.getBuckEventBus());
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

    if (options.isShowOutput() || options.isShowRuleKey()) {
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

    ImmutableSet<BuildTarget> matchingBuildTargets;
    try {
      matchingBuildTargets = ImmutableSet.copyOf(
          getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Parse the entire action graph, or (if targets are specified),
    // only the specified targets and their dependencies..
    TargetGraph graph;
    try {
      if (matchingBuildTargets.isEmpty()) {
        graph = getParser().buildTargetGraphForTargetNodeSpecs(
            ImmutableList.of(
                new TargetNodePredicateSpec(
                    Predicates.<TargetNode<?>>alwaysTrue(),
                    getProjectFilesystem().getIgnorePaths())),
            options.getDefaultIncludes(),
            getBuckEventBus(),
            console,
            environment,
            options.getEnableProfiling());
      } else {
        graph = getParser().buildTargetGraphForBuildTargets(
            matchingBuildTargets,
            options.getDefaultIncludes(),
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
      matchingNodes = getMatchingNodes(
          graph,
          new TargetsCommandPredicate(
              graph,
              buildRuleTypesBuilder.build(),
              referencedFiles.relativePathsUnderProjectRoot,
              matchingBuildTargets.isEmpty() ?
                  Optional.<ImmutableSet<BuildTarget>>absent() :
                  Optional.of(matchingBuildTargets)));
    }

    // Print out matching targets in alphabetical order.
    if (options.getPrintJson()) {
      try {
        printJsonForTargets(matchingNodes, options.getDefaultIncludes());
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

  @VisibleForTesting
  SortedMap<String, TargetNode<?>> getMatchingNodes(
      TargetGraph graph,
      final TargetsCommandPredicate predicate) {
    // Traverse the DependencyGraph and select all of the nodes that accepted by Predicate.
    AbstractBottomUpTraversal<TargetNode<?>, SortedMap<String, TargetNode<?>>> traversal =
        new AbstractBottomUpTraversal<TargetNode<?>, SortedMap<String, TargetNode<?>>>(graph) {

      final SortedMap<String, TargetNode<?>> matchingNodes = Maps.newTreeMap();

      @Override
      public void visit(TargetNode<?> node) {
        if (predicate.apply(node)) {
          matchingNodes.put(node.getBuildTarget().getFullyQualifiedName(), node);
        }
      }

      @Override
      public SortedMap<String, TargetNode<?>> getResult() {
        return matchingNodes;
      }
    };

    traversal.traverse();
    return traversal.getResult();
  }

  @Override
  String getUsageIntro() {
    return "prints the list of buildable targets";
  }

  @VisibleForTesting
  void printJsonForTargets(
      SortedMap<String, TargetNode<?>> buildIndex,
      Iterable<String> defaultIncludes)
      throws BuildFileParseException, IOException, InterruptedException {
    ImmutableList<String> includesCopy = ImmutableList.copyOf(defaultIncludes);
    printJsonForTargetsInternal(buildIndex, includesCopy);
  }

  private void printJsonForTargetsInternal(
      SortedMap<String, TargetNode<?>> buildIndex,
      ImmutableList<String> defaultIncludes)
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
            defaultIncludes,
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
        if (name.equals(buildTarget.getShortName())) {
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
        buildTarget = validateBuildTargetForFullyQualifiedTarget(alias, options);
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
   * Assumes at least one target is specified.
   * Prints each of the specified targets, followed by the rule key, output path or both, depending
   * on what flags are passed in.
   */
  private int doShowRules(TargetsCommandOptions options) throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> matchingBuildTargets;
    try {
      matchingBuildTargets = ImmutableSet.copyOf(
          getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (matchingBuildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ActionGraph graph;
    try {
      TargetGraph targetGraph = getParser().buildTargetGraphForBuildTargets(
          matchingBuildTargets,
          options.getDefaultIncludes(),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
      graph = targetGraphTransformer.apply(targetGraph);
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    for (BuildTarget target : ImmutableSortedSet.copyOf(matchingBuildTargets)) {
      BuildRule rule = Preconditions.checkNotNull(graph.findBuildRuleByTarget(target));
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(target.getFullyQualifiedName());
      if (options.isShowRuleKey()) {
        builder.add(rule.getRuleKey().toString());
      }
      if (options.isShowOutput()) {
        Path outputPath = rule.getPathToOutputFile();
        if (outputPath != null) {
          builder.add(outputPath.toString());
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
      String target, TargetsCommandOptions options) throws IOException, InterruptedException {
    BuildTarget buildTarget;
    try {
      buildTarget = options.getBuildTargetForFullyQualifiedTarget(target);
    } catch (NoSuchBuildTargetException e) {
      return null;
    }

    // Get all valid targets in our target directory by reading the build file.

    List<Map<String, Object>> ruleObjects;
    Parser parser = getParser();
    try {
      ruleObjects = parser.parseBuildFile(
          getRepository().getAbsolutePathToBuildFile(buildTarget),
          options.getDefaultIncludes(),
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
      if (name.equals(buildTarget.getShortName())) {
        return buildTarget.getFullyQualifiedName();
      }
    }
    return null;
  }

  static class TargetsCommandPredicate implements Predicate<TargetNode<?>> {

    private final TargetGraph graph;
    private final ImmutableSet<BuildRuleType> buildRuleTypes;
    @Nullable
    private ImmutableSet<Path> referencedInputs;
    private final Set<Path> basePathOfTargets;
    private final Set<TargetNode<?>> dependentTargets;
    private final Optional<ImmutableSet<BuildTarget>> matchingTargets;

    /**
     * @param buildRuleTypes A {@link TargetNode}'s {@link BuildRuleType} must be contained in this
     *     set for it to match. Ignored if empty.
     * @param referencedInputs A {@link TargetNode} must reference at least one of these paths as
     *     input to match the predicate. All the paths must be relative to the project root. Ignored
     *     if empty.
     * @param matchingTargets If present, a {@link TargetNode}'s {@link BuildTarget} must be
     *     contained in this set for it to match.
     */
    public TargetsCommandPredicate(
        TargetGraph targetGraph,
        ImmutableSet<BuildRuleType> buildRuleTypes,
        ImmutableSet<Path> referencedInputs,
        Optional<ImmutableSet<BuildTarget>> matchingTargets) {
      this.graph = targetGraph;
      this.buildRuleTypes = buildRuleTypes;
      this.matchingTargets = matchingTargets;

      if (!referencedInputs.isEmpty()) {
        this.referencedInputs = referencedInputs;
        BuildFileTree tree = new InMemoryBuildFileTree(
            matchingTargets.or(
                FluentIterable
                    .from(graph.getNodes())
                    .transform(HasBuildTarget.TO_TARGET)
                    .toSet()));
        basePathOfTargets = Sets.newHashSet();
        dependentTargets = Sets.newHashSet();
        for (Path input : referencedInputs) {
          Optional<Path> path = tree.getBasePathOfAncestorTarget(input);
          if (path.isPresent()) {
            basePathOfTargets.add(path.get());
          }
        }
      } else {
        basePathOfTargets = ImmutableSet.of();
        dependentTargets = ImmutableSet.of();
      }
    }

    @Override
    public boolean apply(TargetNode<?> node) {
      boolean isDependent = true;
      if (referencedInputs != null) {
        // Indirectly depend on some referenced file.
        isDependent = !Collections.disjoint(graph.getOutgoingNodesFor(node), dependentTargets);

        // Any referenced file, only those with the nearest BuildTarget can
        // directly depend on that file.
        if (!isDependent &&
            basePathOfTargets.contains(node.getBuildTarget().getBasePath())) {
          for (Path input : node.getInputs()) {
            if (referencedInputs.contains(input)) {
              isDependent = true;
              break;
            }
          }
          if (referencedInputs.contains(node.getBuildTarget().getBuildFilePath())) {
            isDependent = true;
          }
        }

        if (isDependent) {
          // Save the node only when exists referenced file
          // and this node depend on at least one referenced file.
          dependentTargets.add(node);
        }
      }

      if (matchingTargets.isPresent() && !matchingTargets.get().contains(node.getBuildTarget())) {
        return false;
      }

      return (isDependent && (buildRuleTypes.isEmpty() || buildRuleTypes.contains(node.getType())));
    }

  }
}
