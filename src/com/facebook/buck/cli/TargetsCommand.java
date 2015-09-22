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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphHashing;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.annotation.Nullable;

public class TargetsCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(TargetsCommand.class);

  // TODO(mbolin): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(name = "--referenced-file",
      aliases = {"--referenced_file"},
      usage = "The referenced file list, --referenced-file file1 file2  ... fileN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(name = "--detect-test-changes",
      usage = "Modifies the --referenced-file and --show-target-hash flags to pretend that " +
          "tarets depend on their tests (experimental)")
  private boolean isDetectTestChanges;

  @Option(name = "--type",
      usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(name = "--print0", usage = "Delimit targets using the ASCII NUL character.")
  private boolean print0;

  @Option(name = "--resolve-alias",
      aliases = {"--resolvealias"},
      usage = "Print the fully-qualified build target for the specified alias[es]")
  private boolean isResolveAlias;

  @Option(name = "--show-output",
      aliases = {"--show_output"},
      usage = "Print the absolute path to the output for each rule after the rule name.")
  private boolean isShowOutput;

  @Option(name = "--show-rulekey",
      aliases = {"--show_rulekey"},
      usage = "Print the RuleKey of each rule after the rule name.")
  private boolean isShowRuleKey;

  @Option(name = "--show-target-hash",
      usage = "Print a stable hash of each target after the target name.")
  private boolean isShowTargetHash;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  public PathArguments.ReferencedFiles getReferencedFiles(Path projectRoot)
      throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
  }

  /** @return {@code true} if {@code --detect-test-changes} was specified. */
  public boolean isDetectTestChanges() {
    return isDetectTestChanges;
  }

  public boolean getPrintJson() {
    return json;
  }

  public boolean isPrint0() {
    return print0;
  }

  /** @return {@code true} if {@code --resolve-alias} was specified. */
  public boolean isResolveAlias() {
    return isResolveAlias;
  }

  /** @return {@code true} if {@code --show-output} was specified. */
  public boolean isShowOutput() {
    return isShowOutput;
  }

  /** @return {@code true} if {@code --show-rulekey} was specified. */
  public boolean isShowRuleKey() {
    return isShowRuleKey;
  }

  /** @return {@code true} if {@code --show-targethash} was specified. */
  public boolean isShowTargetHash() {
    return isShowTargetHash;
  }

  /** @return the name of the build target identified by the specified alias or {@code null}. */
  @Nullable
  public String getBuildTargetForAlias(BuckConfig buckConfig, String alias) {
    return buckConfig.getBuildTargetForAliasAsString(alias);
  }

  /** @return the build target identified by the specified full path or {@code null}. */
  public BuildTarget getBuildTargetForFullyQualifiedTarget(BuckConfig buckConfig, String target)
      throws NoSuchBuildTargetException {
    return buckConfig.getBuildTargetForFullyQualifiedTarget(target);
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    // Exit early if --resolve-alias is passed in: no need to parse any build files.
    if (isResolveAlias()) {
      return doResolveAlias(params);
    }

    if (isShowRuleKey() && isShowTargetHash()) {
      throw new HumanReadableException("Cannot show rule key and target hash at the same time.");
    }

    if (isShowOutput() || isShowRuleKey() || isShowTargetHash()) {
      return doShowRules(params);
    }

    // Verify the --type argument.
    ImmutableSet<String> types = getTypes();
    ImmutableSet.Builder<BuildRuleType> buildRuleTypesBuilder = ImmutableSet.builder();
    for (String name : types) {
      try {
        buildRuleTypesBuilder.add(params.getCell().getBuildRuleType(name));
      } catch (IllegalArgumentException e) {
        params.getConsole().printBuildFailure("Invalid build rule type: " + name);
        return 1;
      }
    }

    // Parse the entire action graph, or (if targets are specified),
    // only the specified targets and their dependencies..
    //
    // TODO(jakubzika):
    // If --detect-test-changes is specified, we need to load the whole graph, because we cannot
    // know which targets can refer to the specified targets or their dependencies in their
    // 'source_under_test'. Once we migrate from 'source_under_test' to 'tests', this should no
    // longer be necessary.
    ParserConfig parserConfig = new ParserConfig(params.getBuckConfig());
    ImmutableSet<BuildTarget> matchingBuildTargets;
    TargetGraph graph;
    try {
      if (getArguments().isEmpty() || isDetectTestChanges()) {
        matchingBuildTargets = ImmutableSet.of();
        graph = params.getParser()
            .buildTargetGraphForTargetNodeSpecs(
                ImmutableList.of(
                    TargetNodePredicateSpec.of(
                        Predicates.<TargetNode<?>>alwaysTrue(),
                        BuildFileSpec.fromRecursivePath(
                            Paths.get(""),
                            params.getCell().getFilesystem().getIgnorePaths()))),
                parserConfig,
                params.getBuckEventBus(),
                params.getConsole(),
                params.getEnvironment(),
                getEnableProfiling()).getSecond();
      } else {
        Pair<ImmutableSet<BuildTarget>, TargetGraph> results = params.getParser()
            .buildTargetGraphForTargetNodeSpecs(
                parseArgumentsAsTargetNodeSpecs(
                    params.getBuckConfig(),
                    params.getCell().getFilesystem().getIgnorePaths(),
                    getArguments()),
                parserConfig,
                params.getBuckEventBus(),
                params.getConsole(),
                params.getEnvironment(),
                getEnableProfiling());
        matchingBuildTargets = results.getFirst();
        graph = results.getSecond();
      }
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    PathArguments.ReferencedFiles referencedFiles = getReferencedFiles(
        params.getCell().getFilesystem().getRootPath());
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
          isDetectTestChanges(),
          parserConfig.getBuildFileName());
    }

    // Print out matching targets in alphabetical order.
    if (getPrintJson()) {
      try {
        printJsonForTargets(params, matchingNodes, new ParserConfig(params.getBuckConfig()));
      } catch (BuildFileParseException e) {
        params.getConsole().printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    } else if (isPrint0()) {
      printNullDelimitedTargets(matchingNodes.keySet(), params.getConsole().getStdOut());
    } else {
      for (String target : matchingNodes.keySet()) {
        params.getConsole().getStdOut().println(target);
      }
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
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
  public String getShortDescription() {
    return "prints the list of buildable targets";
  }

  @VisibleForTesting
  void printJsonForTargets(
      CommandRunnerParams params,
      SortedMap<String, TargetNode<?>> buildIndex,
      ParserConfig parserConfig)
      throws BuildFileParseException, IOException, InterruptedException {
    // Print the JSON representation of the build node for the specified target(s).
    params.getConsole().getStdOut().println("[");

    ObjectMapper mapper = params.getObjectMapper();
    Iterator<TargetNode<?>> valueIterator = buildIndex.values().iterator();

    while (valueIterator.hasNext()) {
      TargetNode<?> targetNode = valueIterator.next();

      SortedMap<String, Object> sortedTargetRule =
          CommandHelper.getBuildTargetRules(params, parserConfig, targetNode);
      if (sortedTargetRule == null) {
        params.getConsole().printErrorText(
            "unable to find rule for target " +
                targetNode.getBuildTarget().getFullyQualifiedName());
        continue;
      }

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
      params.getConsole().getStdOut().println(output);
    }

    params.getConsole().getStdOut().println("]");
  }

  @VisibleForTesting
  static void printNullDelimitedTargets(Iterable<String> targets, PrintStream printStream) {
    for (String target : targets) {
      printStream.print(target + '\0');
    }
  }

  /**
   * Assumes each argument passed to this command is an alias defined in .buckconfig,
   * or a fully qualified (non-alias) target to be verified by checking the build files.
   * Prints the build target that each alias maps to on its own line to standard out.
   */
  private int doResolveAlias(CommandRunnerParams params) throws IOException, InterruptedException {
    List<String> resolvedAliases = Lists.newArrayList();
    for (String alias : getArguments()) {
      String buildTarget;
      if (alias.startsWith("//")) {
        buildTarget = validateBuildTargetForFullyQualifiedTarget(
            params,
            alias,
            params.getParser());
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not a valid target.", alias);
        }
      } else {
        buildTarget = getBuildTargetForAlias(params.getBuckConfig(), alias);
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not an alias.", alias);
        }
      }
      resolvedAliases.add(buildTarget);
    }

    for (String resolvedAlias : resolvedAliases) {
      params.getConsole().getStdOut().println(resolvedAlias);
    }

    return 0;
  }

  /**
   * Assumes at least one target is specified.  Prints each of the
   * specified targets, followed by the rule key, output path, and/or
   * target hash, depending on what flags are passed in.
   */
  private int doShowRules(CommandRunnerParams params) throws IOException, InterruptedException {
    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> matchingBuildTargets;
    TargetGraph targetGraph;
    try {
      Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  params.getCell().getFilesystem().getIgnorePaths(),
                  getArguments()),
              new ParserConfig(params.getBuckConfig()),
              params.getBuckEventBus(),
              params.getConsole(),
              params.getEnvironment(),
              getEnableProfiling());
      matchingBuildTargets = result.getFirst();
      targetGraph = result.getSecond();
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (isShowTargetHash()) {
      return doShowTargetHash(params, matchingBuildTargets);
    } else {
      Optional<ActionGraph> actionGraph;
      if (isShowRuleKey() || isShowOutput()) {
        TargetGraphTransformer targetGraphTransformer = new TargetGraphToActionGraph(
            params.getBuckEventBus(),
            new BuildTargetNodeToBuildRuleTransformer(),
            params.getFileHashCache());
        actionGraph = Optional.of(targetGraphTransformer.apply(targetGraph));
      } else {
        actionGraph = Optional.absent();
      }

      for (BuildTarget target : ImmutableSortedSet.copyOf(matchingBuildTargets)) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        builder.add(target.getFullyQualifiedName());
        if (actionGraph.isPresent()) {
          BuildRule rule = Preconditions.checkNotNull(
              actionGraph.get().findBuildRuleByTarget(target));
          if (isShowRuleKey()) {
            builder.add(rule.getRuleKey().toString());
          }
          if (isShowOutput()) {
            Path outputPath = rule.getPathToOutput();
            if (outputPath != null) {
              builder.add(outputPath.toString());
            }
          }
        }
        params.getConsole().getStdOut().println(Joiner.on(' ').join(builder.build()));
      }
    }

    return 0;
  }

  private int doShowTargetHash(
      CommandRunnerParams params,
      ImmutableSet<BuildTarget> matchingBuildTargets)
      throws IOException, InterruptedException {
    LOG.debug("Getting target hash for %s", matchingBuildTargets);

    ProjectGraphParser projectGraphParser = ProjectGraphParsers.createProjectGraphParser(
        params.getParser(),
        new ParserConfig(params.getBuckConfig()),
        params.getBuckEventBus(),
        params.getConsole(),
        params.getEnvironment(),
        getEnableProfiling());

    // Parse the BUCK files for the targets passed in from the command line and their deps.
    TargetGraph projectGraph = projectGraphParser.buildTargetGraphForTargetNodeSpecs(
        Iterables.transform(matchingBuildTargets, BuildTargetSpec.TO_BUILD_TARGET_SPEC));

    LOG.debug("Built project graph with nodes: %s", projectGraph.getNodes());

    Iterable<BuildTarget> matchingBuildTargetsWithTests;
    final TargetGraph projectGraphWithTests;
    if (isDetectTestChanges()) {
      ImmutableSet<BuildTarget> explicitTestTargets;
      explicitTestTargets = TargetGraphAndTargets.getExplicitTestTargets(
          matchingBuildTargets,
          projectGraph,
          true);
      LOG.debug("Got explicit test targets: %s", explicitTestTargets);
      matchingBuildTargetsWithTests =
          Sets.union(matchingBuildTargets, explicitTestTargets);

      // Parse the BUCK files for the tests of the targets passed in from the command line.
      projectGraphWithTests = projectGraphParser.buildTargetGraphForTargetNodeSpecs(
          Iterables.transform(
              matchingBuildTargetsWithTests,
              BuildTargetSpec.TO_BUILD_TARGET_SPEC));
    } else {
      matchingBuildTargetsWithTests = matchingBuildTargets;
      projectGraphWithTests = projectGraph;
    }

    // Hash each target's rule description and contents of any files.
    ImmutableMap<BuildTarget, HashCode> buildTargetHashes =
        TargetGraphHashing.hashTargetGraph(
            params.getCell().getFilesystem(),
            projectGraphWithTests,
            params.getParser().getBuildTargetHashCodeCache(),
            matchingBuildTargetsWithTests);

    // Now that we've parsed all the BUCK files for the rules and their tests,
    // we can re-walk the graph for each target passed in to the command,
    // hashing the target, its deps, the tests, and their deps.
    for (BuildTarget target : matchingBuildTargets) {
      TargetNode<?> targetNode = Preconditions.checkNotNull(
          projectGraphWithTests.get(target),
          "Could not find target %s in project graph",
          target);
      TargetGraph subGraph = projectGraphWithTests.getSubgraph(ImmutableSet.of(targetNode));

      Hasher hasher = Hashing.sha1().newHasher();
      ImmutableSortedSet.Builder<TargetNode<?>> nodesWithDepsAndTests =
          ImmutableSortedSet.naturalOrder();
      // Add the target and its deps.
      nodesWithDepsAndTests.addAll(subGraph.getNodes());

      if (isDetectTestChanges()) {
        // Add the tests and their deps.
        nodesWithDepsAndTests.addAll(FluentIterable
                .from(subGraph.getNodes())
                .transformAndConcat(
                    new Function<TargetNode<?>, Iterable<TargetNode<?>>>() {
                      @Override
                      public Iterable<TargetNode<?>> apply(TargetNode<?> node) {
                        return projectGraphWithTests.getAll(
                            TargetNodes.getTestTargetsForNode(node));
                      }
                    }));
      }

      LOG.debug("Hashing target %s with dependent nodes %s", target, nodesWithDepsAndTests.build());
      for (TargetNode<?> nodeToHash : nodesWithDepsAndTests.build()) {
        HashCode dependencyHash = buildTargetHashes.get(
            nodeToHash.getBuildTarget());
        Preconditions.checkNotNull(dependencyHash, "Couldn't get hash for node: %s", nodeToHash);
        hasher.putBytes(dependencyHash.asBytes());
      }
      params.getConsole().getStdOut().format(
          "%s %s\n",
          target.getFullyQualifiedName(),
          hasher.hash().toString());
    }

    return 0;
  }

  /**
   * Verify that the given target is a valid full-qualified (non-alias) target.
   */
  @Nullable
  @VisibleForTesting
  String validateBuildTargetForFullyQualifiedTarget(
      CommandRunnerParams params,
      String target,
      Parser parser) throws IOException, InterruptedException {
    BuildTarget buildTarget;
    try {
      buildTarget = getBuildTargetForFullyQualifiedTarget(params.getBuckConfig(), target);
    } catch (NoSuchBuildTargetException e) {
      return null;
    }

    // Get all valid targets in our target directory by reading the build file.
    List<Map<String, Object>> ruleObjects;
    try {
      ruleObjects = parser.parseBuildFile(
          params.getCell().getAbsolutePathToBuildFile(buildTarget),
          new ParserConfig(params.getBuckConfig()),
          params.getEnvironment(),
          params.getConsole(),
          params.getBuckEventBus());
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
