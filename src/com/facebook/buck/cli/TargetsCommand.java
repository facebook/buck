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
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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

  public TargetsCommand(CommandRunnerParams params) {
    super(params);
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

    // Find the build targets that match the specified options.
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
    PartialGraph graph;
    try {
      if (matchingBuildTargets.isEmpty()) {
        graph = PartialGraph.createFullGraph(
            getProjectFilesystem(),
            options.getDefaultIncludes(),
            getParser(),
            getBuckEventBus(),
            console,
            environment);
      } else {
        graph = PartialGraph.createPartialGraphIncludingRoots(
            matchingBuildTargets,
            options.getDefaultIncludes(),
            getParser(),
            getBuckEventBus(),
            console,
            environment);
      }
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    SortedMap<String, BuildRule> matchingBuildRules = getMatchingBuildRules(
        graph.getActionGraph(),
        new TargetsCommandPredicate(
            graph,
            buildRuleTypesBuilder.build(),
            options.getReferencedFiles(getProjectFilesystem().getRootPath()),
            matchingBuildTargets));

    // Print out matching targets in alphabetical order.
    if (options.getPrintJson()) {
      try {
        printJsonForTargets(matchingBuildRules, options.getDefaultIncludes());
      } catch (BuildFileParseException e) {
        console.printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    } else {
      printTargetsList(matchingBuildRules, options.isShowOutput(), options.isShowRuleKey());
    }

    return 0;
  }

  @VisibleForTesting
  void printTargetsList(SortedMap<String, BuildRule> matchingBuildRules,
      boolean showOutput,
      boolean showRuleKey) throws IOException {
    for (Map.Entry<String, BuildRule> target : matchingBuildRules.entrySet()) {
      String output = target.getKey();
      BuildRule buildRule = target.getValue();
      if (showRuleKey) {
        output += " " + buildRule.getRuleKey();
      }
      if (showOutput) {
        Path outputPath = buildRule.getPathToOutputFile();
        if (outputPath != null) {
          output += " " + outputPath;
        }
      }
      getStdOut().println(output);
    }
  }

  @VisibleForTesting
  SortedMap<String, BuildRule> getMatchingBuildRules(
      final ActionGraph graph,
      final TargetsCommandPredicate predicate) {
    // Traverse the DependencyGraph and select all of the rules that accepted by Predicate.
    AbstractBottomUpTraversal<BuildRule, SortedMap<String, BuildRule>> traversal =
        new AbstractBottomUpTraversal<BuildRule, SortedMap<String, BuildRule>>(graph) {

      final SortedMap<String, BuildRule> matchingBuildRules = Maps.newTreeMap();

      @Override
      public void visit(BuildRule rule) {
        if (predicate.apply(rule)) {
          matchingBuildRules.put(rule.getFullyQualifiedName(), rule);
        }
      }

      @Override
      public SortedMap<String, BuildRule> getResult() {
        return matchingBuildRules;
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
  void printJsonForTargets(SortedMap<String, BuildRule> buildIndex,
      Iterable<String> defaultIncludes)
      throws BuildFileParseException, IOException, InterruptedException {
    ImmutableList<String> includesCopy = ImmutableList.copyOf(defaultIncludes);
    printJsonForTargetsInternal(buildIndex, includesCopy);
  }

  private void printJsonForTargetsInternal(
      SortedMap<String, BuildRule> buildIndex,
      ImmutableList<String> defaultIncludes)
      throws BuildFileParseException, IOException, InterruptedException {
    // Print the JSON representation of the build rule for the specified target(s).
    getStdOut().println("[");

    ObjectMapper mapper = getObjectMapper();
    Iterator<BuildRule> valueIterator = buildIndex.values().iterator();

    while (valueIterator.hasNext()) {
      BuildRule buildRule = valueIterator.next();
      BuildTarget buildTarget = buildRule.getBuildTarget();

      List<Map<String, Object>> rules;
      try {
        Path buildFile = getRepository().getAbsolutePathToBuildFile(buildTarget);
        rules = getParser().parseBuildFile(
            buildFile,
            defaultIncludes,
            environment,
            console);
      } catch (BuildTargetException e) {
        console.printErrorText(
            "unable to find rule for target " + buildTarget.getFullyQualifiedName());
        continue;
      }

      // Find the build rule information that corresponds to this build buildTarget.
      Map<String, Object> targetRule = null;
      for (Map<String, Object> rule : rules) {
        String name = (String) rule.get("name");
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

      Path outputPath = buildRule.getPathToOutputFile();

      if (outputPath != null) {
        targetRule.put("buck.output_file", outputPath.toString());
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
          console);
    } catch (BuildTargetException | BuildFileParseException e) {
      // TODO(devjasta): this doesn't smell right!
      return null;
    }

    // Check that the given target is a valid target.
    for (Map<String, Object> rule : ruleObjects) {
      String name = (String) rule.get("name");
      if (name.equals(buildTarget.getShortName())) {
        return buildTarget.getFullyQualifiedName();
      }
    }
    return null;
  }

  static class TargetsCommandPredicate implements Predicate<BuildRule> {

    private ActionGraph graph;
    private ImmutableSet<BuildRuleType> buildRuleTypes;
    private ImmutableSet<Path> referencedInputs;
    private Set<Path> basePathOfTargets;
    private Set<BuildRule> dependentTargets;
    private Set<BuildTarget> matchingBuildRules;

    /**
     * @param referencedPaths All of these paths must be relative to the project root.
     */
    public TargetsCommandPredicate(
        PartialGraph partialGraph,
        ImmutableSet<BuildRuleType> buildRuleTypes,
        ImmutableSet<String> referencedPaths,
        ImmutableSet<BuildTarget> matchingBuildRules) {
      this.graph = partialGraph.getActionGraph();
      this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
      this.matchingBuildRules = Preconditions.checkNotNull(matchingBuildRules);

      Preconditions.checkNotNull(referencedPaths);
      if (!referencedPaths.isEmpty()) {
        this.referencedInputs = MorePaths.asPaths(referencedPaths);
        BuildFileTree tree = new InMemoryBuildFileTree(partialGraph.getTargets());
        basePathOfTargets = Sets.newHashSet();
        dependentTargets = Sets.newHashSet();
        for (Path input : referencedInputs) {
          basePathOfTargets.add(tree.getBasePathOfAncestorTarget(input));
        }
      } else {
        basePathOfTargets = ImmutableSet.of();
        dependentTargets = ImmutableSet.of();
      }
    }

    @Override
    public boolean apply(BuildRule rule) {
      boolean isDependent = true;
      if (referencedInputs != null) {
        // Indirectly depend on some referenced file.
        isDependent = !Collections.disjoint(graph.getOutgoingNodesFor(rule), dependentTargets);

        // Any referenced file, only those with the nearest BuildTarget can
        // directly depend on that file.
        if (!isDependent &&
            basePathOfTargets.contains(rule.getBuildTarget().getBasePath())) {
          for (Path input : rule.getInputs()) {
            if (referencedInputs.contains(input)) {
              isDependent = true;
              break;
            }
          }
          if (referencedInputs.contains(rule.getBuildTarget().getBuildFilePath())) {
            isDependent = true;
          }
        }

        if (isDependent) {
          // Save the rule only when exists referenced file
          // and this rule depend on at least one referenced file.
          dependentTargets.add(rule);
        }
      }

      if (!matchingBuildRules.isEmpty() &&
          !matchingBuildRules.contains(rule.getBuildTarget())) {
        return false;
      }

      return (isDependent && (buildRuleTypes.isEmpty() || buildRuleTypes.contains(rule.getType())));
    }

  }
}
