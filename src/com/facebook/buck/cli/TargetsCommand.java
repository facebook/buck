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

import com.facebook.buck.apple.BuildRuleWithAppleBundle;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.hashing.FilePathHashLoader;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetGraphAndTargetNodes;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphHashing;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;

public class TargetsCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(TargetsCommand.class);

  // TODO(bolinfest): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(name = "--referenced-file",
      aliases = {"--referenced_file"},
      usage = "The referenced file list, --referenced-file file1 file2  ... fileN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(name = "--detect-test-changes",
      usage = "Modifies the --referenced-file and --show-target-hash flags to pretend that " +
          "targets depend on their tests (experimental)")
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

  private enum TargetHashFileMode {
    PATHS_AND_CONTENTS,
    PATHS_ONLY,
  }

  @Option(name = "--target-hash-file-mode",
      usage = "Modifies computation of target hashes. If set to PATHS_AND_CONTENTS (the " +
          "default), the contents of all files referenced from the targets will be used to " +
          "compute the target hash. If set to PATHS_ONLY, only files' paths contribute to the " +
          "hash. See also --target-hash-modified-paths.")
  private TargetHashFileMode targetHashFileMode = TargetHashFileMode.PATHS_AND_CONTENTS;

  @Option(name = "--target-hash-modified-paths",
      usage = "Modifies computation of target hashes. Only effective when " +
          "--target-hash-file-mode is set to PATHS_ONLY. If a target or its dependencies " +
          "reference a file from this set, the target's hash will be different than if this " +
          "option was omitted. Otherwise, the target's hash will be the same as if this option " +
          "was omitted.",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> targetHashModifiedPaths;

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

  /** @return mode passed to {@code --target-hash-file-mode}. */
  public TargetHashFileMode getTargetHashFileMode() {
    return targetHashFileMode;
  }

  /**
   * @return set of paths passed to {@code --assume-modified-files} if either
   *         {@code --assume-modified-files} or {@code --assume-no-modified-files} is used,
   *         absent otherwise.
   */
  public ImmutableSet<Path> getTargetHashModifiedPaths() throws IOException {
    return FluentIterable.from(targetHashModifiedPaths.get())
        .transform(MorePaths.TO_PATH)
        .toSet();
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (isShowRuleKey() && isShowTargetHash()) {
      throw new HumanReadableException("Cannot show rule key and target hash at the same time.");
    }

    try (CommandThreadManager pool = new CommandThreadManager(
        "Targets",
        params.getBuckConfig().getWorkQueueExecutionOrder(),
        getConcurrencyLimit(params.getBuckConfig()))) {
      ListeningExecutorService executor = pool.getExecutor();

      // Exit early if --resolve-alias is passed in: no need to parse any build files.
      if (isResolveAlias()) {
        return ResolveAliasHelper.resolveAlias(
            params,
            executor,
            getEnableProfiling(),
            getArguments());
      }

      return runWithExecutor(params, executor);
    }
  }

  private int runWithExecutor(
      CommandRunnerParams params,
      ListeningExecutorService executor) throws IOException, InterruptedException {
    ImmutableMap<String, ShowOptions> showRulesResult =
        ImmutableMap.of();

    if (isShowOutput() || isShowRuleKey() || isShowTargetHash()) {
      try {
        if (getArguments().isEmpty()) {
          throw new HumanReadableException("Must specify at least one build target.");
        }

        TargetGraphAndBuildTargets targetGraphAndBuildTargetsForShowRules = params.getParser()
            .buildTargetGraphForTargetNodeSpecs(
                params.getBuckEventBus(),
                params.getCell(),
                getEnableProfiling(),
                executor,
                parseArgumentsAsTargetNodeSpecs(
                    params.getBuckConfig(),
                    getArguments()),
            /* ignoreBuckAutodepsFiles */ false);

        showRulesResult = computeShowRules(
            params,
            executor,
            TargetGraphAndTargetNodes.fromTargetGraphAndBuildTargets(
                targetGraphAndBuildTargetsForShowRules));
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(
            "Error getting rules: %s",
            e.getHumanReadableErrorMessage());
      } catch (BuildTargetException | BuildFileParseException | CycleException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
                MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      if (!getPrintJson()) {
        printShowRules(showRulesResult, params);
        return 0;
      }
    }

    Optional<ImmutableSet<BuildRuleType>> buildRuleTypes = getBuildRuleTypesFromParams(params);
    if (!buildRuleTypes.isPresent()) {
      return 1;
    }

    // Parse the entire action graph, or (if targets are specified),
    // only the specified targets and their dependencies..
    //
    // TODO(k21):
    // If --detect-test-changes is specified, we need to load the whole graph, because we cannot
    // know which targets can refer to the specified targets or their dependencies in their
    // 'source_under_test'. Once we migrate from 'source_under_test' to 'tests', this should no
    // longer be necessary.
    Optional<TargetGraphAndBuildTargets> graphAndTargets =
        buildTargetGraphAndTargets(params, executor);
    if (!graphAndTargets.isPresent()) {
      return 1;
    }

    SortedMap<String, TargetNode<?>> matchingNodes = getMatchingNodes(
        params,
        graphAndTargets.get(),
        buildRuleTypes);

    return printResults(params, executor, matchingNodes, showRulesResult);
  }

  /**
   * Print out matching targets in alphabetical order.
   */
  private int printResults(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      SortedMap<String, TargetNode<?>> matchingNodes,
      ImmutableMap<String, ShowOptions> showRulesResult) throws IOException, InterruptedException {
    if (getPrintJson()) {
      try {
        printJsonForTargets(params, executor, matchingNodes, showRulesResult);
      } catch (BuildFileParseException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
                MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
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

  private SortedMap<String, TargetNode<?>> getMatchingNodes(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ImmutableSet<BuildRuleType>> buildRuleTypes
  ) throws IOException {
    PathArguments.ReferencedFiles referencedFiles = getReferencedFiles(
        params.getCell().getFilesystem().getRootPath());
    SortedMap<String, TargetNode<?>> matchingNodes;
    // If all of the referenced files are paths outside the project root, then print nothing.
    if (!referencedFiles.absolutePathsOutsideProjectRootOrNonExistingPaths.isEmpty() &&
        referencedFiles.relativePathsUnderProjectRoot.isEmpty()) {
      matchingNodes = ImmutableSortedMap.of();
    } else {
      ParserConfig parserConfig = new ParserConfig(params.getBuckConfig());

      ImmutableSet<BuildTarget> matchingBuildTargets = targetGraphAndBuildTargets.getBuildTargets();
      matchingNodes = getMatchingNodes(
          targetGraphAndBuildTargets.getTargetGraph(),
          referencedFiles.relativePathsUnderProjectRoot.isEmpty() ?
              Optional.<ImmutableSet<Path>>absent() :
              Optional.of(referencedFiles.relativePathsUnderProjectRoot),
          matchingBuildTargets.isEmpty() ?
              Optional.<ImmutableSet<BuildTarget>>absent() :
              Optional.of(matchingBuildTargets),
          buildRuleTypes.get().isEmpty() ?
              Optional.<ImmutableSet<BuildRuleType>>absent() :
              buildRuleTypes,
          isDetectTestChanges(),
          parserConfig.getBuildFileName());
    }
    return matchingNodes;
  }

  private Optional<TargetGraphAndBuildTargets> buildTargetGraphAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executor) throws IOException, InterruptedException {
    try {
      boolean ignoreBuckAutodepsFiles = false;
      if (getArguments().isEmpty() || isDetectTestChanges()) {
        return Optional.of(TargetGraphAndBuildTargets.builder()
            .setBuildTargets(ImmutableSet.<BuildTarget>of())
            .setTargetGraph(params.getParser()
                .buildTargetGraphForTargetNodeSpecs(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableProfiling(),
                    executor,
                    ImmutableList.of(
                        TargetNodePredicateSpec.of(
                            Predicates.<TargetNode<?>>alwaysTrue(),
                            BuildFileSpec.fromRecursivePath(
                                Paths.get("")))),
                    ignoreBuckAutodepsFiles).getTargetGraph()).build());
      } else {
        return Optional.of(
            params.getParser()
                .buildTargetGraphForTargetNodeSpecs(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableProfiling(),
                    executor,
                    parseArgumentsAsTargetNodeSpecs(
                        params.getBuckConfig(),
                        getArguments()),
                    ignoreBuckAutodepsFiles));
      }
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
              MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return Optional.absent();
    }
  }

  private Optional<ImmutableSet<BuildRuleType>> getBuildRuleTypesFromParams(
      CommandRunnerParams params) {
    ImmutableSet<String> types = getTypes();
    ImmutableSet.Builder<BuildRuleType> buildRuleTypesBuilder = ImmutableSet.builder();
    for (String name : types) {
      try {
        buildRuleTypesBuilder.add(params.getCell().getBuildRuleType(name));
      } catch (IllegalArgumentException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
                "Invalid build rule type: " + name));
        return Optional.absent();
      }
    }
    return Optional.of(buildRuleTypesBuilder.build());
  }

  private void printShowRules(
      Map<String, ShowOptions> showRulesResult,
      CommandRunnerParams params) {
    for (Entry<String, ShowOptions> entry : showRulesResult.entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(entry.getKey());
      ShowOptions showOptions = entry.getValue();
      if (showOptions.getRuleKey().isPresent()) {
        builder.add(showOptions.getRuleKey().get());
      }
      if (showOptions.getOutputPath().isPresent()) {
        builder.add(showOptions.getOutputPath().get());
      }
      if (showOptions.getTargetHash().isPresent()) {
        builder.add(showOptions.getTargetHash().get());
      }
      params.getConsole().getStdOut().println(Joiner.on(' ').join(builder.build()));
    }

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
            Optional<TargetNode<?>> testNode = graph.getOptional(testTarget);
            if (!testNode.isPresent()) {
              throw new HumanReadableException(
                  "'%s' (test of '%s') is not in the target graph.",
                  testTarget,
                  node);
            }
            extraEdgesBuilder.put(testNode.get(), node);
          }
        }

        if (node.getConstructorArg() instanceof HasSourceUnderTest) {
          ImmutableSortedSet<BuildTarget> sourceUnderTest =
              ((HasSourceUnderTest) node.getConstructorArg()).getSourceUnderTest();
          for (BuildTarget sourceTarget : sourceUnderTest) {
            TargetNode<?> sourceNode = graph.get(sourceTarget);
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
      ListeningExecutorService executor,
      SortedMap<String, TargetNode<?>> buildIndex,
      ImmutableMap<String, ShowOptions> showRulesResult)
      throws BuildFileParseException, IOException, InterruptedException {
    // Print the JSON representation of the build node for the specified target(s).
    params.getConsole().getStdOut().println("[");

    ObjectMapper mapper = params.getObjectMapper();
    Iterator<Entry<String, TargetNode<?>>> mapIterator = buildIndex.entrySet().iterator();

    while (mapIterator.hasNext()) {
      Entry<String, TargetNode<?>> current = mapIterator.next();
      String target = current.getKey();
      TargetNode<?> targetNode = current.getValue();
      SortedMap<String, Object> sortedTargetRule;
      sortedTargetRule = params.getParser().getRawTargetNode(
          params.getBuckEventBus(),
          params.getCell(),
          getEnableProfiling(),
          executor,
          targetNode);
      if (sortedTargetRule == null) {
        params.getConsole().printErrorText(
            "unable to find rule for target " +
                targetNode.getBuildTarget().getFullyQualifiedName());
        continue;
      }
      ShowOptions showOptions = showRulesResult.get(target);
      if (showOptions != null) {
        putIfValuePresent(
            ShowOptionsName.RULE_KEY.getName(),
            showOptions.getRuleKey(),
            sortedTargetRule);
        putIfValuePresent(
            ShowOptionsName.OUTPUT_PATH.getName(),
            showOptions.getOutputPath(),
            sortedTargetRule);
        putIfValuePresent(
            ShowOptionsName.TARGET_HASH.getName(),
            showOptions.getTargetHash(),
            sortedTargetRule);
      }

      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      try {
        mapper.writerWithDefaultPrettyPrinter().writeValue(stringWriter, sortedTargetRule);
      } catch (IOException e) {
        // Shouldn't be possible while writing to a StringWriter...
        throw new RuntimeException(e);
      }
      String output = stringWriter.getBuffer().toString();
      if (mapIterator.hasNext()) {
        output += ",";
      }
      params.getConsole().getStdOut().println(output);
    }

    params.getConsole().getStdOut().println("]");
  }

  private void putIfValuePresent(
      String key,
      Optional<String> value,
      SortedMap<String, Object> targetMap) {
    if (value.isPresent()) {
      targetMap.put(key, value.get());
    }
  }

  @VisibleForTesting
  static void printNullDelimitedTargets(Iterable<String> targets, PrintStream printStream) {
    for (String target : targets) {
      printStream.print(target + '\0');
    }
  }

  /**
   * Assumes at least one target is specified.  Computes each of the
   * specified targets, followed by the rule key, output path, and/or
   * target hash, depending on what flags are passed in.
   * @return  An immutable map consisting of result of show options
   * for to each target rule
   */
  private ImmutableMap<String, ShowOptions> computeShowRules(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
        CycleException {

    Map<String, ShowOptions.Builder> showOptionBuilderMap = new HashMap<>();
    if (isShowTargetHash()) {
      computeShowTargetHash(
          params,
          executor,
          targetGraphAndTargetNodes,
          showOptionBuilderMap);
    }

    // We only need the action graph if we're showing the output or the keys, and the
    // RuleKeyBuilderFactory if we're showing the keys.
    Optional<ActionGraph> actionGraph = Optional.absent();
    Optional<BuildRuleResolver> buildRuleResolver = Optional.absent();
    Optional<RuleKeyBuilderFactory> ruleKeyBuilderFactory = Optional.absent();
    if (isShowRuleKey() || isShowOutput()) {
      TargetGraphTransformer targetGraphTransformer = new TargetGraphToActionGraph(
          params.getBuckEventBus(),
          new DefaultTargetNodeToBuildRuleTransformer());
      ActionGraphAndResolver result = Preconditions.checkNotNull(
          targetGraphTransformer.apply(targetGraphAndTargetNodes.getTargetGraph()));
      actionGraph = Optional.of(result.getActionGraph());
      buildRuleResolver = Optional.of(result.getResolver());
      if (isShowRuleKey()) {
        ruleKeyBuilderFactory = Optional.<RuleKeyBuilderFactory>of(
            new DefaultRuleKeyBuilderFactory(
                params.getFileHashCache(),
                new SourcePathResolver(result.getResolver())));
      }
    }

    for (TargetNode<?> targetNode :
        ImmutableSortedSet.copyOf(targetGraphAndTargetNodes.getTargetNodes())) {
      ShowOptions.Builder showOptionsBuilder =
          getShowOptionBuilder(showOptionBuilderMap, targetNode);
      Preconditions.checkNotNull(showOptionsBuilder);
      if (actionGraph.isPresent()) {
        BuildRule rule = buildRuleResolver.get().requireRule(targetNode.getBuildTarget());
        if (isShowRuleKey()) {
          showOptionsBuilder.setRuleKey(ruleKeyBuilderFactory.get().build(rule).toString());
        }
        if (isShowOutput()) {
          Optional<Path> outputPath = getUserFacingOutputPath(rule);
          if (outputPath.isPresent()) {
            showOptionsBuilder.setOutputPath(outputPath.get().toString());
          }
        }
      }
    }

    ImmutableMap.Builder<String, ShowOptions> builder =  new ImmutableMap.Builder<>();
    for (Entry<String, ShowOptions.Builder> entry : showOptionBuilderMap.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().build());
    }
    return builder.build();
  }

  public static Optional<Path> getUserFacingOutputPath(BuildRule rule) {
    Path outputPath;
    if (rule instanceof BuildRuleWithAppleBundle) {
      outputPath = ((BuildRuleWithAppleBundle) rule).getAppleBundle().getPathToOutput();
    } else {
      outputPath = rule.getPathToOutput();
    }
    return Optional.fromNullable(outputPath);
  }

  private TargetGraphAndTargetNodes computeTargetsAndGraphToShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes
  ) throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {

    if (isDetectTestChanges()) {
      ImmutableSet<BuildTarget> explicitTestTargets = TargetGraphAndTargets.getExplicitTestTargets(
          targetGraphAndTargetNodes,
          true);
      LOG.debug("Got explicit test targets: %s", explicitTestTargets);

      Iterable<BuildTarget> matchingBuildTargetsWithTests =
          mergeBuildTargets(targetGraphAndTargetNodes.getTargetNodes(), explicitTestTargets);

      // Parse the BUCK files for the tests of the targets passed in from the command line.
      TargetGraph targetGraphWithTests = params.getParser().buildTargetGraph(
          params.getBuckEventBus(),
          params.getCell(),
          getEnableProfiling(),
          executor,
          matchingBuildTargetsWithTests);

      return TargetGraphAndTargetNodes.builder()
          .setTargetGraph(targetGraphWithTests)
          .setTargetNodes(targetGraphWithTests.getAll(matchingBuildTargetsWithTests))
          .build();
    } else {
      return targetGraphAndTargetNodes;
    }
  }

  private Iterable<BuildTarget> mergeBuildTargets(
      Iterable<TargetNode<?>> targetNodes,
      Iterable<BuildTarget> buildTargets) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();

    targetsBuilder.addAll(buildTargets);

    for (TargetNode<?> node : targetNodes) {
      targetsBuilder.add(node.getBuildTarget());
    }
    return targetsBuilder.build();
  }

  private FileHashLoader createOrGetFileHashLoader(CommandRunnerParams params) throws IOException {
    TargetHashFileMode targetHashFileMode = getTargetHashFileMode();
    switch (targetHashFileMode) {
      case PATHS_AND_CONTENTS:
        return params.getFileHashCache();
      case PATHS_ONLY:
        return new FilePathHashLoader(
            params.getCell().getRoot(),
            getTargetHashModifiedPaths());
    }
    throw new IllegalStateException(
        "Invalid value for target hash file mode: " + targetHashFileMode);
  }

  private void computeShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes,
      Map<String, ShowOptions.Builder> showRulesResult)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
      CycleException {
    LOG.debug("Getting target hash for %s", targetGraphAndTargetNodes.getTargetNodes());

    TargetGraphAndTargetNodes targetGraphAndNodes =
        computeTargetsAndGraphToShowTargetHash(
            params,
            executor,
            targetGraphAndTargetNodes);

    TargetGraph targetGraphWithTests = targetGraphAndNodes.getTargetGraph();

    FileHashLoader fileHashLoader = createOrGetFileHashLoader(params);

    // Hash each target's rule description and contents of any files.
    ImmutableMap<TargetNode<?>, HashCode> buildTargetHashes =
        TargetGraphHashing.hashTargetGraph(
            params.getCell(),
            targetGraphWithTests,
            fileHashLoader,
            targetGraphAndNodes.getTargetNodes());

    ImmutableMap<TargetNode<?>, HashCode> finalHashes = rehashWithTestsIfNeeded(
        targetGraphWithTests,
        targetGraphAndTargetNodes.getTargetNodes(),
        buildTargetHashes);

    for (TargetNode<?> targetNode : targetGraphAndTargetNodes.getTargetNodes()) {
      processTargetHash(targetNode, showRulesResult, finalHashes);
    }
  }

  private ImmutableMap<TargetNode<?>, HashCode> rehashWithTestsIfNeeded(
      final TargetGraph targetGraphWithTests,
      Iterable<TargetNode<?>> inputTargets,
      ImmutableMap<TargetNode<?>, HashCode> buildTargetHashes) throws CycleException {

    if (!isDetectTestChanges()) {
      return buildTargetHashes;
    }

    final Function<TargetNode<?>, Iterable<TargetNode<?>>> getTestTargetNodesFunction =
        new Function<TargetNode<?>, Iterable<TargetNode<?>>>() {
          @Override
          public Iterable<TargetNode<?>> apply(TargetNode<?> node) {
            return targetGraphWithTests.getAll(
                TargetNodes.getTestTargetsForNode(node));
          }
        };

    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(new GraphTraversable<TargetNode<?>>() {
          @Override
          public Iterator<TargetNode<?>> findChildren(TargetNode<?> node) {
            return targetGraphWithTests.getAll(node.getDeps()).iterator();
          }
        });

    Map<TargetNode<?>, HashCode> hashesWithTests = Maps.newHashMap();

    for (TargetNode<?> node : traversal.traverse(inputTargets)) {
      hashNodeWithDependencies(
          targetGraphWithTests,
          buildTargetHashes,
          hashesWithTests,
          getTestTargetNodesFunction,
          node);
    }

    return ImmutableMap.copyOf(hashesWithTests);
  }

  private void hashNodeWithDependencies(
      TargetGraph targetGraphWithTests,
      ImmutableMap<TargetNode<?>, HashCode> buildTargetHashes,
      Map<TargetNode<?>, HashCode> hashesWithTests,
      Function<TargetNode<?>, Iterable<TargetNode<?>>> getTestTargetNodesFunction,
      TargetNode<?> node) {
    HashCode nodeHashCode = buildTargetHashes.get(node);
    Preconditions.checkNotNull(nodeHashCode, "Cannot determine hash for target: " + node);

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(nodeHashCode.asBytes());

    Iterable<TargetNode<?>> nodes = targetGraphWithTests.getAll(node.getDeps());
    LOG.debug("Hashing target %s with dependent nodes %s", node, nodes);
    for (TargetNode<?> nodeToHash : nodes) {
      HashCode dependencyHash = hashesWithTests.get(nodeToHash);
      Preconditions.checkNotNull(dependencyHash, "Couldn't get hash for node: %s", nodeToHash);
      hasher.putBytes(dependencyHash.asBytes());
    }

    if (isDetectTestChanges()) {
      for (TargetNode<?> nodeToHash :
          Preconditions.checkNotNull(getTestTargetNodesFunction.apply(node))) {
        HashCode testNodeHashCode = buildTargetHashes.get(nodeToHash);
        Preconditions.checkNotNull(testNodeHashCode, "Cannot find hash for target " + nodeToHash);
        hasher.putBytes(testNodeHashCode.asBytes());
      }
    }
    hashesWithTests.put(node, hasher.hash());
  }

  private void processTargetHash(
      TargetNode<?> targetNode,
      Map<String, ShowOptions.Builder> showRulesResult,
      ImmutableMap<TargetNode<?>, HashCode> finalHashes) {
    ShowOptions.Builder showOptionsBuilder = getShowOptionBuilder(showRulesResult, targetNode);
    HashCode hashCode = finalHashes.get(targetNode);
    Preconditions.checkNotNull(hashCode, "Cannot find hash for target " + targetNode);
    showOptionsBuilder.setTargetHash(hashCode.toString());
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

  private ShowOptions.Builder getShowOptionBuilder(
      Map<String, ShowOptions.Builder> showRulesBuilderMap,
      TargetNode<?> targetNode) {
    String targetFullyQualifiedName = targetNode.getBuildTarget().getFullyQualifiedName();
    if (!showRulesBuilderMap.containsKey(targetFullyQualifiedName)) {
      ShowOptions.Builder builder = ShowOptions.builder();
      showRulesBuilderMap.put(targetFullyQualifiedName, builder);
      return builder;
    }
    return showRulesBuilderMap.get(targetFullyQualifiedName);
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractShowOptions {
    public abstract Optional<String> getOutputPath();

    public abstract Optional<String> getRuleKey();

    public abstract Optional<String> getTargetHash();
  }

  private enum ShowOptionsName {
    OUTPUT_PATH("buck.outputPath"),
    TARGET_HASH("buck.targetHash"),
    RULE_KEY("buck.ruleKey");

    private String name;

    ShowOptionsName(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

}
