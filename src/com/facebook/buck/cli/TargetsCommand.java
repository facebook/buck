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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.hashing.FilePathHashLoader;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetGraphAndTargetNodes;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphHashing;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class TargetsCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(TargetsCommand.class);

  // TODO(mbolin): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(
    name = "--referenced-file",
    aliases = {"--referenced_file"},
    usage = "The referenced file list, --referenced-file file1 file2  ... fileN --other_option",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(
    name = "--detect-test-changes",
    usage =
        "Modifies the --referenced-file and --show-target-hash flags to pretend that "
            + "targets depend on their tests (experimental)"
  )
  private boolean isDetectTestChanges;

  @Option(
    name = "--type",
    usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(name = "--print0", usage = "Delimit targets using the ASCII NUL character.")
  private boolean print0;

  @Option(
    name = "--resolve-alias",
    aliases = {"--resolvealias"},
    usage = "Print the fully-qualified build target for the specified alias[es]"
  )
  private boolean isResolveAlias;

  @Option(
    name = "--show-cell-path",
    aliases = {"--show_cell_path"},
    usage = "Print the absolute path of the cell for each rule after the rule name."
  )
  private boolean isShowCellPath;

  @Option(
    name = "--show-output",
    aliases = {"--show_output"},
    usage =
        "Print the path to the output, relative to the cell path, for each rule after the "
            + "rule name. Use '--show-full-output' to obtain the full absolute path."
  )
  private boolean isShowOutput;

  @Option(
    name = "--show-full-output",
    aliases = {"--show_full_output"},
    usage = "Print the absolute path to the output, for each rule after the rule name."
  )
  private boolean isShowFullOutput;

  @Option(
    name = "--show-rulekey",
    aliases = {"--show_rulekey"},
    usage = "Print the RuleKey of each rule after the rule name."
  )
  private boolean isShowRuleKey;

  @Option(
    name = "--show-target-hash",
    usage = "Print a stable hash of each target after the target name."
  )
  private boolean isShowTargetHash;

  private enum TargetHashFileMode {
    PATHS_AND_CONTENTS,
    PATHS_ONLY,
  }

  @Option(
    name = "--target-hash-file-mode",
    usage =
        "Modifies computation of target hashes. If set to PATHS_AND_CONTENTS (the "
            + "default), the contents of all files referenced from the targets will be used to "
            + "compute the target hash. If set to PATHS_ONLY, only files' paths contribute to the "
            + "hash. See also --target-hash-modified-paths."
  )
  private TargetHashFileMode targetHashFileMode = TargetHashFileMode.PATHS_AND_CONTENTS;

  @Option(
    name = "--target-hash-modified-paths",
    usage =
        "Modifies computation of target hashes. Only effective when "
            + "--target-hash-file-mode is set to PATHS_ONLY. If a target or its dependencies "
            + "reference a file from this set, the target's hash will be different than if this "
            + "option was omitted. Otherwise, the target's hash will be the same as if this option "
            + "was omitted.",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> targetHashModifiedPaths;

  @Option(
    name = "--output-attributes",
    usage =
        "List of attributes to output, --output-attributes attr1 att2 ... attrN. "
            + "Attributes can be regular expressions. ",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> outputAttributes;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  public PathArguments.ReferencedFiles getReferencedFiles(Path projectRoot) throws IOException {
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

  /** @return {@code true} if {@code --show-cell-path} was specified. */
  public boolean isShowCellPath() {
    return isShowCellPath;
  }

  /** @return {@code true} if {@code --show-output} was specified. */
  public boolean isShowOutput() {
    return isShowOutput;
  }

  /** @return {@code true} if {@code --show-output} was specified. */
  public boolean isShowFullOutput() {
    return isShowFullOutput;
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

  /** @return attributes pass in {@code --output-attributes}. */
  public ImmutableSet<String> getOutputAttributes() {
    return outputAttributes.get();
  }

  /**
   * Determines if the results should be in JSON format. This is true either when explicitly
   * required by the {@code --json} argument or when there is at least one item in the {@code
   * --output-attributes} arguments.
   *
   * <p>The {@code --output--attributes} arguments implicitly enables JSON format because there is
   * currently no way to output attributes in non-JSON format. Also, it keeps this command
   * consistent with the query command.
   */
  public boolean shouldUseJsonFormat() {
    return getPrintJson() || !getOutputAttributes().isEmpty();
  }

  /**
   * @return set of paths passed to {@code --assume-modified-files} if either {@code
   *     --assume-modified-files} or {@code --assume-no-modified-files} is used, absent otherwise.
   */
  public ImmutableSet<Path> getTargetHashModifiedPaths() {
    return targetHashModifiedPaths
        .get()
        .stream()
        .map(Paths::get)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (isShowRuleKey() && isShowTargetHash()) {
      throw new HumanReadableException("Cannot show rule key and target hash at the same time.");
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Targets", getConcurrencyLimit(params.getBuckConfig()))) {
      ListeningExecutorService executor = pool.getExecutor();

      // Exit early if --resolve-alias is passed in: no need to parse any build files.
      if (isResolveAlias()) {
        return ResolveAliasHelper.resolveAlias(
            params, executor, getEnableParserProfiling(), getArguments());
      }

      return runWithExecutor(params, executor);
    } catch (BuildTargetException | BuildFileParseException | CycleException | VersionException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  private int runWithExecutor(CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException, VersionException {
    Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses =
        getDescriptionClassFromParams(params);
    if (!descriptionClasses.isPresent()) {
      return 1;
    }

    if (isShowCellPath()
        || isShowOutput()
        || isShowFullOutput()
        || isShowRuleKey()
        || isShowTargetHash()) {
      ImmutableMap<BuildTarget, ShowOptions> showRulesResult;
      TargetGraphAndBuildTargets targetGraphAndBuildTargetsForShowRules =
          buildTargetGraphAndTargetsForShowRules(params, executor, descriptionClasses);
      boolean useVersioning =
          isShowRuleKey() || isShowOutput() || isShowFullOutput()
              ? params.getBuckConfig().getBuildVersions()
              : params.getBuckConfig().getTargetsVersions();
      targetGraphAndBuildTargetsForShowRules =
          useVersioning
              ? toVersionedTargetGraph(params, targetGraphAndBuildTargetsForShowRules)
              : targetGraphAndBuildTargetsForShowRules;
      showRulesResult =
          computeShowRules(
              params,
              executor,
              TargetGraphAndTargetNodes.fromTargetGraphAndBuildTargets(
                  targetGraphAndBuildTargetsForShowRules));

      if (shouldUseJsonFormat()) {
        Iterable<TargetNode<?, ?>> matchingNodes =
            targetGraphAndBuildTargetsForShowRules
                .getTargetGraph()
                .getAll(targetGraphAndBuildTargetsForShowRules.getBuildTargets());
        printJsonForTargets(
            params, executor, matchingNodes, showRulesResult, getOutputAttributes());
      } else {
        printShowRules(showRulesResult, params);
      }
      return 0;
    }

    return printResults(
        params,
        executor,
        getMatchingNodes(params, buildTargetGraphAndTargets(params, executor), descriptionClasses));
  }

  private TargetGraphAndBuildTargets buildTargetGraphAndTargetsForShowRules(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    if (getArguments().isEmpty()) {
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
      TargetGraphAndBuildTargets completeTargetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  ImmutableList.of(
                      TargetNodePredicateSpec.of(
                          x -> true,
                          BuildFileSpec.fromRecursivePath(
                              Paths.get(""), params.getCell().getRoot()))),
                  parserConfig.getDefaultFlavorsMode());
      SortedMap<String, TargetNode<?, ?>> matchingNodes =
          getMatchingNodes(params, completeTargetGraphAndBuildTargets, descriptionClasses);

      Iterable<BuildTarget> buildTargets =
          FluentIterable.from(matchingNodes.values()).transform(TargetNode::getBuildTarget);

      return TargetGraphAndBuildTargets.builder()
          .setTargetGraph(completeTargetGraphAndBuildTargets.getTargetGraph())
          .setBuildTargets(buildTargets)
          .build();
    } else {
      return params
          .getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()));
    }
  }

  /** Print out matching targets in alphabetical order. */
  private int printResults(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      SortedMap<String, TargetNode<?, ?>> matchingNodes)
      throws BuildFileParseException {
    if (shouldUseJsonFormat()) {
      printJsonForTargets(
          params, executor, matchingNodes.values(), ImmutableMap.of(), getOutputAttributes());
    } else if (isPrint0()) {
      printNullDelimitedTargets(matchingNodes.keySet(), params.getConsole().getStdOut());
    } else {
      for (String target : matchingNodes.keySet()) {
        params.getConsole().getStdOut().println(target);
      }
    }
    return 0;
  }

  private SortedMap<String, TargetNode<?, ?>> getMatchingNodes(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses)
      throws IOException {
    PathArguments.ReferencedFiles referencedFiles =
        getReferencedFiles(params.getCell().getFilesystem().getRootPath());
    SortedMap<String, TargetNode<?, ?>> matchingNodes;
    // If all of the referenced files are paths outside the project root, then print nothing.
    if (!referencedFiles.absolutePathsOutsideProjectRootOrNonExistingPaths.isEmpty()
        && referencedFiles.relativePathsUnderProjectRoot.isEmpty()) {
      matchingNodes = ImmutableSortedMap.of();
    } else {
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);

      ImmutableSet<BuildTarget> matchingBuildTargets = targetGraphAndBuildTargets.getBuildTargets();
      matchingNodes =
          getMatchingNodes(
              targetGraphAndBuildTargets.getTargetGraph(),
              referencedFiles.relativePathsUnderProjectRoot.isEmpty()
                  ? Optional.empty()
                  : Optional.of(referencedFiles.relativePathsUnderProjectRoot),
              matchingBuildTargets.isEmpty() ? Optional.empty() : Optional.of(matchingBuildTargets),
              descriptionClasses.get().isEmpty() ? Optional.empty() : descriptionClasses,
              isDetectTestChanges(),
              parserConfig.getBuildFileName());
    }
    return matchingNodes;
  }

  private TargetGraphAndBuildTargets buildTargetGraphAndTargets(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          VersionException {
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    // Parse the entire action graph, or (if targets are specified), only the specified targets and
    // their dependencies. If we're detecting test changes we need the whole graph as tests are not
    // dependencies.
    TargetGraphAndBuildTargets targetGraphAndBuildTargets;
    if (getArguments().isEmpty() || isDetectTestChanges()) {
      targetGraphAndBuildTargets =
          TargetGraphAndBuildTargets.builder()
              .setBuildTargets(ImmutableSet.of())
              .setTargetGraph(
                  params
                      .getParser()
                      .buildTargetGraphForTargetNodeSpecs(
                          params.getBuckEventBus(),
                          params.getCell(),
                          getEnableParserProfiling(),
                          executor,
                          ImmutableList.of(
                              TargetNodePredicateSpec.of(
                                  x -> true,
                                  BuildFileSpec.fromRecursivePath(
                                      Paths.get(""), params.getCell().getRoot()))),
                          parserConfig.getDefaultFlavorsMode())
                      .getTargetGraph())
              .build();
    } else {
      targetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()),
                  parserConfig.getDefaultFlavorsMode());
    }
    return params.getBuckConfig().getTargetsVersions()
        ? toVersionedTargetGraph(params, targetGraphAndBuildTargets)
        : targetGraphAndBuildTargets;
  }

  @SuppressWarnings("unchecked")
  private Optional<ImmutableSet<Class<? extends Description<?>>>> getDescriptionClassFromParams(
      CommandRunnerParams params) {
    ImmutableSet<String> types = getTypes();
    ImmutableSet.Builder<Class<? extends Description<?>>> descriptionClassesBuilder =
        ImmutableSet.builder();
    for (String name : types) {
      try {
        BuildRuleType type = params.getCell().getBuildRuleType(name);
        Description<?> description = params.getCell().getDescription(type);
        descriptionClassesBuilder.add((Class<? extends Description<?>>) description.getClass());
      } catch (IllegalArgumentException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe("Invalid build rule type: " + name));
        return Optional.empty();
      }
    }
    return Optional.of(descriptionClassesBuilder.build());
  }

  private void printShowRules(
      Map<BuildTarget, ShowOptions> showRulesResult, CommandRunnerParams params) {
    for (Entry<BuildTarget, ShowOptions> entry :
        ImmutableSortedMap.copyOf(showRulesResult).entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(entry.getKey().getFullyQualifiedName());
      ShowOptions showOptions = entry.getValue();
      if (showOptions.getRuleKey().isPresent()) {
        builder.add(showOptions.getRuleKey().get());
      }
      if (isShowCellPath()) {
        builder.add(entry.getKey().getCellPath().toString());
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
   *     depend on at least one of those.
   * @param matchingBuildTargets If present, the result will be limited to the specified targets.
   * @param buildRuleTypes If present, the result will be limited to targets with the specified
   *     types.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *     are testing.
   * @return A map of target names to target nodes.
   */
  @VisibleForTesting
  ImmutableSortedMap<String, TargetNode<?, ?>> getMatchingNodes(
      TargetGraph graph,
      Optional<ImmutableSet<Path>> referencedFiles,
      final Optional<ImmutableSet<BuildTarget>> matchingBuildTargets,
      final Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses,
      boolean detectTestChanges,
      String buildFileName) {
    ImmutableSet<TargetNode<?, ?>> directOwners;
    if (referencedFiles.isPresent()) {
      BuildFileTree buildFileTree =
          new InMemoryBuildFileTree(
              graph
                  .getNodes()
                  .stream()
                  .map(TargetNode::getBuildTarget)
                  .collect(MoreCollectors.toImmutableSet()));
      directOwners =
          FluentIterable.from(graph.getNodes())
              .filter(new DirectOwnerPredicate(buildFileTree, referencedFiles.get(), buildFileName))
              .toSet();
    } else {
      directOwners = graph.getNodes();
    }
    Iterable<TargetNode<?, ?>> selectedReferrers =
        FluentIterable.from(getDependentNodes(graph, directOwners, detectTestChanges))
            .filter(
                targetNode -> {
                  if (matchingBuildTargets.isPresent()
                      && !matchingBuildTargets.get().contains(targetNode.getBuildTarget())) {
                    return false;
                  }

                  if (descriptionClasses.isPresent()
                      && !descriptionClasses
                          .get()
                          .contains(targetNode.getDescription().getClass())) {
                    return false;
                  }

                  return true;
                });
    ImmutableSortedMap.Builder<String, TargetNode<?, ?>> matchingNodesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (TargetNode<?, ?> targetNode : selectedReferrers) {
      matchingNodesBuilder.put(targetNode.getBuildTarget().getFullyQualifiedName(), targetNode);
    }
    return matchingNodesBuilder.build();
  }

  /**
   * @param graph A graph used to resolve dependencies between targets.
   * @param nodes A set of nodes.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *     are testing.
   * @return A set of all nodes that transitively depend on {@code nodes} (a superset of {@code
   *     nodes}).
   */
  private static ImmutableSet<TargetNode<?, ?>> getDependentNodes(
      final TargetGraph graph, ImmutableSet<TargetNode<?, ?>> nodes, boolean detectTestChanges) {
    ImmutableMultimap.Builder<TargetNode<?, ?>, TargetNode<?, ?>> extraEdgesBuilder =
        ImmutableMultimap.builder();

    if (detectTestChanges) {
      for (TargetNode<?, ?> node : graph.getNodes()) {
        if (node.getConstructorArg() instanceof HasTests) {
          ImmutableSortedSet<BuildTarget> tests = ((HasTests) node.getConstructorArg()).getTests();
          for (BuildTarget testTarget : tests) {
            Optional<TargetNode<?, ?>> testNode = graph.getOptional(testTarget);
            if (!testNode.isPresent()) {
              throw new HumanReadableException(
                  "'%s' (test of '%s') is not in the target graph.", testTarget, node);
            }
            extraEdgesBuilder.put(testNode.get(), node);
          }
        }
      }
    }
    final ImmutableMultimap<TargetNode<?, ?>, TargetNode<?, ?>> extraEdges =
        extraEdgesBuilder.build();

    final ImmutableSet.Builder<TargetNode<?, ?>> builder = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<TargetNode<?, ?>> traversal =
        new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(nodes) {
          @Override
          public ImmutableSet<TargetNode<?, ?>> visit(TargetNode<?, ?> targetNode) {
            builder.add(targetNode);
            return FluentIterable.from(graph.getIncomingNodesFor(targetNode))
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
      Iterable<TargetNode<?, ?>> targetNodes,
      ImmutableMap<BuildTarget, ShowOptions> showRulesResult,
      ImmutableSet<String> outputAttributes)
      throws BuildFileParseException {
    PatternsMatcher attributesPatternsMatcher = new PatternsMatcher(outputAttributes);

    // Print the JSON representation of the build node for the specified target(s).
    params.getConsole().getStdOut().println("[");

    Iterator<TargetNode<?, ?>> targetNodeIterator = targetNodes.iterator();

    while (targetNodeIterator.hasNext()) {
      TargetNode<?, ?> targetNode = targetNodeIterator.next();
      Map<String, Object> sortedTargetRule;
      sortedTargetRule =
          params
              .getParser()
              .getRawTargetNode(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  targetNode);
      if (sortedTargetRule == null) {
        params
            .getConsole()
            .printErrorText(
                "unable to find rule for target "
                    + targetNode.getBuildTarget().getFullyQualifiedName());
        continue;
      }

      sortedTargetRule = attributesPatternsMatcher.filterMatchingMapKeys(sortedTargetRule);

      ShowOptions showOptions = showRulesResult.get(targetNode.getBuildTarget());
      if (showOptions != null) {
        putIfValuePresentAndMatches(
            ShowOptionsName.RULE_KEY.getName(),
            showOptions.getRuleKey(),
            sortedTargetRule,
            attributesPatternsMatcher);
        putIfValuePresentAndMatches(
            ShowOptionsName.OUTPUT_PATH.getName(),
            showOptions.getOutputPath(),
            sortedTargetRule,
            attributesPatternsMatcher);
        putIfValuePresentAndMatches(
            ShowOptionsName.TARGET_HASH.getName(),
            showOptions.getTargetHash(),
            sortedTargetRule,
            attributesPatternsMatcher);
      }
      String fullyQualifiedNameAttribute = "fully_qualified_name";
      if (attributesPatternsMatcher.matches(fullyQualifiedNameAttribute)) {
        sortedTargetRule.put(
            fullyQualifiedNameAttribute, targetNode.getBuildTarget().getFullyQualifiedName());
      }
      String cellPathAttribute = "buck.cell_path";
      if (isShowCellPath() && attributesPatternsMatcher.matches(cellPathAttribute)) {
        sortedTargetRule.put(cellPathAttribute, targetNode.getBuildTarget().getCellPath());
      }

      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      try {
        ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, sortedTargetRule);
      } catch (IOException e) {
        // Shouldn't be possible while writing to a StringWriter...
        throw new RuntimeException(e);
      }
      String output = stringWriter.getBuffer().toString();
      if (targetNodeIterator.hasNext()) {
        output += ",";
      }
      params.getConsole().getStdOut().println(output);
    }

    params.getConsole().getStdOut().println("]");
  }

  private void putIfValuePresentAndMatches(
      String key,
      Optional<String> value,
      Map<String, Object> targetMap,
      PatternsMatcher patternsMatcher) {
    if (value.isPresent() && patternsMatcher.matches(key)) {
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
   * Assumes at least one target is specified. Computes each of the specified targets, followed by
   * the rule key, output path, and/or target hash, depending on what flags are passed in.
   *
   * @return An immutable map consisting of result of show options for to each target rule
   */
  private ImmutableMap<BuildTarget, ShowOptions> computeShowRules(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException {

    Map<BuildTarget, ShowOptions.Builder> showOptionBuilderMap = new HashMap<>();
    if (isShowTargetHash()) {
      computeShowTargetHash(params, executor, targetGraphAndTargetNodes, showOptionBuilderMap);
    }

    // We only need the action graph if we're showing the output or the keys, and the
    // RuleKeyFactory if we're showing the keys.
    Optional<ActionGraph> actionGraph = Optional.empty();
    Optional<BuildRuleResolver> buildRuleResolver = Optional.empty();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    if (isShowRuleKey() || isShowOutput() || isShowFullOutput()) {
      ActionGraphAndResolver result =
          params
              .getActionGraphCache()
              .getActionGraph(
                  params.getBuckEventBus(),
                  params.getBuckConfig().isActionGraphCheckingEnabled(),
                  params.getBuckConfig().isSkipActionGraphCache(),
                  targetGraphAndTargetNodes.getTargetGraph(),
                  params.getBuckConfig().getKeySeed());
      actionGraph = Optional.of(result.getActionGraph());
      buildRuleResolver = Optional.of(result.getResolver());
      if (isShowRuleKey()) {
        SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(result.getResolver());
        ruleKeyFactory =
            Optional.of(
                new DefaultRuleKeyFactory(
                    new RuleKeyFieldLoader(params.getBuckConfig().getKeySeed()),
                    params.getFileHashCache(),
                    new SourcePathResolver(ruleFinder),
                    ruleFinder));
      }
    }

    for (TargetNode<?, ?> targetNode : targetGraphAndTargetNodes.getTargetNodes()) {
      ShowOptions.Builder showOptionsBuilder =
          getShowOptionBuilder(showOptionBuilderMap, targetNode.getBuildTarget());
      Preconditions.checkNotNull(showOptionsBuilder);
      if (actionGraph.isPresent()) {
        BuildRule rule = buildRuleResolver.get().requireRule(targetNode.getBuildTarget());
        if (isShowRuleKey()) {
          showOptionsBuilder.setRuleKey(ruleKeyFactory.get().build(rule).toString());
        }
        if (isShowOutput() || isShowFullOutput()) {
          Optional<Path> outputPath =
              getUserFacingOutputPath(
                  new SourcePathResolver(new SourcePathRuleFinder(buildRuleResolver.get())),
                  rule,
                  isShowFullOutput(),
                  params.getBuckConfig().getBuckOutCompatLink());
          if (outputPath.isPresent()) {
            showOptionsBuilder.setOutputPath(outputPath.get().toString());
          }
        }
      }
    }

    ImmutableMap.Builder<BuildTarget, ShowOptions> builder = new ImmutableMap.Builder<>();
    for (Entry<BuildTarget, ShowOptions.Builder> entry : showOptionBuilderMap.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().build());
    }
    return builder.build();
  }

  static Optional<Path> getUserFacingOutputPath(
      SourcePathResolver pathResolver,
      BuildRule rule,
      boolean absolute,
      boolean buckOutCompatLink) {
    Optional<Path> outputPathOptional =
        Optional.ofNullable(rule.getSourcePathToOutput()).map(pathResolver::getRelativePath);

    // When using buck out compat mode, we favor using the default buck output path in the UI, so
    // amend the output paths when this is set.
    if (outputPathOptional.isPresent() && buckOutCompatLink) {
      BuckPaths paths = rule.getProjectFilesystem().getBuckPaths();
      if (outputPathOptional.get().startsWith(paths.getConfiguredBuckOut())) {
        outputPathOptional =
            Optional.of(
                paths
                    .getBuckOut()
                    .resolve(
                        outputPathOptional
                            .get()
                            .subpath(
                                paths.getConfiguredBuckOut().getNameCount(),
                                outputPathOptional.get().getNameCount())));
      }
    }

    if (absolute) {
      return outputPathOptional.map(rule.getProjectFilesystem()::resolve);
    } else {
      return outputPathOptional;
    }
  }

  private TargetGraphAndTargetNodes computeTargetsAndGraphToShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {

    if (isDetectTestChanges()) {
      ImmutableSet<BuildTarget> explicitTestTargets =
          TargetGraphAndTargets.getExplicitTestTargets(
              targetGraphAndTargetNodes
                  .getTargetGraph()
                  .getSubgraph(targetGraphAndTargetNodes.getTargetNodes())
                  .getNodes()
                  .iterator());
      LOG.debug("Got explicit test targets: %s", explicitTestTargets);

      Iterable<BuildTarget> matchingBuildTargetsWithTests =
          mergeBuildTargets(targetGraphAndTargetNodes.getTargetNodes(), explicitTestTargets);

      // Parse the BUCK files for the tests of the targets passed in from the command line.
      TargetGraph targetGraphWithTests =
          params
              .getParser()
              .buildTargetGraph(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
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
      Iterable<TargetNode<?, ?>> targetNodes, Iterable<BuildTarget> buildTargets) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();

    targetsBuilder.addAll(buildTargets);

    for (TargetNode<?, ?> node : targetNodes) {
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
        return new FilePathHashLoader(params.getCell().getRoot(), getTargetHashModifiedPaths());
    }
    throw new IllegalStateException(
        "Invalid value for target hash file mode: " + targetHashFileMode);
  }

  private void computeShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      TargetGraphAndTargetNodes targetGraphAndTargetNodes,
      Map<BuildTarget, ShowOptions.Builder> showRulesResult)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException {
    LOG.debug("Getting target hash for %s", targetGraphAndTargetNodes.getTargetNodes());

    TargetGraphAndTargetNodes targetGraphAndNodesWithTests =
        computeTargetsAndGraphToShowTargetHash(params, executor, targetGraphAndTargetNodes);

    TargetGraph targetGraphWithTests = targetGraphAndNodesWithTests.getTargetGraph();

    FileHashLoader fileHashLoader = createOrGetFileHashLoader(params);

    // Hash each target's rule description and contents of any files.
    ImmutableMap<BuildTarget, HashCode> buildTargetHashes =
        new TargetGraphHashing(
                params.getBuckEventBus(),
                targetGraphWithTests,
                fileHashLoader,
                targetGraphAndNodesWithTests.getTargetNodes())
            .setNumThreads(params.getBuckConfig().getNumThreads())
            .hashTargetGraph();

    ImmutableMap<BuildTarget, HashCode> finalHashes =
        rehashWithTestsIfNeeded(
            targetGraphWithTests, targetGraphAndTargetNodes.getTargetNodes(), buildTargetHashes);

    for (TargetNode<?, ?> targetNode : targetGraphAndTargetNodes.getTargetNodes()) {
      processTargetHash(targetNode.getBuildTarget(), showRulesResult, finalHashes);
    }
  }

  private ImmutableMap<BuildTarget, HashCode> rehashWithTestsIfNeeded(
      final TargetGraph targetGraphWithTests,
      Iterable<TargetNode<?, ?>> inputTargets,
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes)
      throws CycleException {

    if (!isDetectTestChanges()) {
      return buildTargetHashes;
    }

    AcyclicDepthFirstPostOrderTraversal<TargetNode<?, ?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(
            node -> targetGraphWithTests.getAll(node.getParseDeps()).iterator());

    Map<BuildTarget, HashCode> hashesWithTests = new HashMap<>();

    for (TargetNode<?, ?> node : traversal.traverse(inputTargets)) {
      hashNodeWithDependencies(buildTargetHashes, hashesWithTests, node);
    }

    return ImmutableMap.copyOf(hashesWithTests);
  }

  private void hashNodeWithDependencies(
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes,
      Map<BuildTarget, HashCode> hashesWithTests,
      TargetNode<?, ?> node) {
    HashCode nodeHashCode = getHashCodeOrThrow(buildTargetHashes, node.getBuildTarget());

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(nodeHashCode.asBytes());

    Iterable<BuildTarget> dependentTargets = node.getParseDeps();
    LOG.debug("Hashing target %s with dependent nodes %s", node, dependentTargets);
    for (BuildTarget targetToHash : dependentTargets) {
      HashCode dependencyHash = getHashCodeOrThrow(hashesWithTests, targetToHash);
      hasher.putBytes(dependencyHash.asBytes());
    }

    if (isDetectTestChanges()) {
      for (BuildTarget targetToHash :
          Preconditions.checkNotNull(TargetNodes.getTestTargetsForNode(node))) {
        HashCode testNodeHashCode = getHashCodeOrThrow(buildTargetHashes, targetToHash);
        hasher.putBytes(testNodeHashCode.asBytes());
      }
    }
    hashesWithTests.put(node.getBuildTarget(), hasher.hash());
  }

  private void processTargetHash(
      BuildTarget buildTarget,
      Map<BuildTarget, ShowOptions.Builder> showRulesResult,
      ImmutableMap<BuildTarget, HashCode> finalHashes) {
    ShowOptions.Builder showOptionsBuilder = getShowOptionBuilder(showRulesResult, buildTarget);
    HashCode hashCode = getHashCodeOrThrow(finalHashes, buildTarget);
    showOptionsBuilder.setTargetHash(hashCode.toString());
  }

  private static HashCode getHashCodeOrThrow(
      Map<BuildTarget, HashCode> buildTargetHashCodes, BuildTarget buildTarget) {
    HashCode hashCode = buildTargetHashCodes.get(buildTarget);
    return Preconditions.checkNotNull(hashCode, "Cannot find hash for target: %s", buildTarget);
  }

  private static class DirectOwnerPredicate implements Predicate<TargetNode<?, ?>> {

    private final ImmutableSet<Path> referencedInputs;
    private final ImmutableSet<Path> basePathOfTargets;
    private final String buildFileName;

    /**
     * @param referencedInputs A {@link TargetNode} must reference at least one of these paths as
     *     input to match the predicate. All the paths must be relative to the project root. Ignored
     *     if empty.
     */
    public DirectOwnerPredicate(
        BuildFileTree buildFileTree, ImmutableSet<Path> referencedInputs, String buildFileName) {
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
    public boolean apply(TargetNode<?, ?> node) {
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
      Map<BuildTarget, ShowOptions.Builder> showRulesBuilderMap, BuildTarget buildTarget) {
    if (!showRulesBuilderMap.containsKey(buildTarget)) {
      ShowOptions.Builder builder = ShowOptions.builder();
      showRulesBuilderMap.put(buildTarget, builder);
      return builder;
    }
    return showRulesBuilderMap.get(buildTarget);
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
