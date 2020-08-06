/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.impl.InMemoryBuildFileTree;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphHashing;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDepsPackage;
import com.facebook.buck.core.parser.BuildTargetPatternToBuildPackagePathKey;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.TargetNodePredicateSpec;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.support.cli.config.JsonAttributeFormat;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.hashing.FilePathHashLoader;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
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
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(
      name = "--detect-test-changes",
      usage =
          "Modifies the --referenced-file and --show-target-hash flags to pretend that "
              + "targets depend on their tests (experimental)")
  private boolean isDetectTestChanges;

  @Option(name = "--show-parse-state", usage = "Serializes all the build targets (experimental)")
  private boolean isShowParseState;

  @Option(
      name = "--type",
      usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(
      name = "--dot",
      usage =
          "Output rule keys in DOT format, only works with both --show-rulekey and --show-transitive-rulekeys")
  private boolean dot;

  @Option(name = "--print0", usage = "Delimit targets using the ASCII NUL character.")
  private boolean print0;

  @Option(
      name = "--resolve-alias",
      aliases = {"--resolvealias"},
      usage = "Print the fully-qualified build target for the specified alias[es]")
  private boolean isResolveAlias;

  @Option(
      name = "--show-cell-path",
      aliases = {"--show_cell_path"},
      usage = "Print the absolute path of the cell for each rule after the rule name.")
  private boolean isShowCellPath;

  @Option(
      name = "--show-output",
      aliases = {"--show_output"},
      usage =
          "Print the path to the output, relative to the cell path, for each rule after the "
              + "rule name. Use '--show-full-output' to obtain the full absolute path.")
  private boolean isShowOutput;

  @Option(
      name = "--show-outputs",
      usage =
          "Print paths to outputs, relative to the cell path, for each rule after the rule name.")
  private boolean isShowOutputs;

  @Option(
      name = "--show-full-output",
      aliases = {"--show_full_output"},
      usage = "Print the absolute path to the output, for each rule after the rule name.")
  private boolean isShowFullOutput;

  @Option(
      name = "--show-rulekey",
      aliases = {"--show_rulekey"},
      forbids = {"--show-target-hash"},
      usage =
          "Print the RuleKey of each rule after the rule name. "
              + "Incompatible with '--show-target-hash'.")
  private boolean isShowRuleKey;

  @Option(
      name = "--show-transitive-rulekeys",
      aliases = {"--show-transitive-rulekeys"},
      usage = "Show rule keys of transitive deps as well.")
  private boolean isShowTransitiveRuleKeys;

  @Option(
      name = "--show-target-hash",
      forbids = {"--show-rulekey"},
      usage =
          "Print a stable hash of each target after the target name. "
              + "Incompatible with '--show-rulekey'.")
  private boolean isShowTargetHash;

  @Option(
      name = "--show-transitive-target-hashes",
      aliases = {"--show-transitive-target-hashes"},
      usage = "Show target hashes of transitive deps as well.")
  private boolean isShowTransitiveTargetHashes;

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
              + "hash. See also --target-hash-modified-paths.")
  private TargetHashFileMode targetHashFileMode = TargetHashFileMode.PATHS_AND_CONTENTS;

  private enum TargetHashFunction {
    SHA1,
    MURMUR_HASH3
  }

  @Option(
      name = "--target-hash-function",
      usage =
          "Determines the hash function to use for computing target hashes. By default, "
              + "murmurhash3 will be used. Not that this doesn't control how files are hashed.")
  private TargetHashFunction targetHashFunction = TargetHashFunction.MURMUR_HASH3;

  @Option(
      name = "--target-hash-modified-paths",
      usage =
          "Modifies computation of target hashes. Only effective when "
              + "--target-hash-file-mode is set to PATHS_ONLY. If a target or its dependencies "
              + "reference a file from this set, the target's hash will be different than if this "
              + "option was omitted. Otherwise, the target's hash will be the same as if this option "
              + "was omitted.",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> targetHashModifiedPaths;

  @Option(
      name = "--output-attributes",
      usage =
          "List of attributes to output, --output-attributes attr1 att2 ... attrN. "
              + "Attributes can be regular expressions. ",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> outputAttributes;

  @Nullable
  @Option(
      name = "--rulekeys-log-path",
      usage = "If set, log a binary representation of rulekeys to this file.")
  private String ruleKeyLogPath = null;

  @Argument private List<String> arguments = new ArrayList<>();

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  public PathArguments.ReferencedFiles getReferencedFiles(AbsPath projectRoot) throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
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
    return json || !outputAttributes.get().isEmpty();
  }

  /**
   * Determines if the results should be in DOT format. This is true when {@code --dot} parameter is
   * specified along with {@code --show-rulekey} and {@code --show-transitive-rulekeys}
   */
  private boolean shouldUseDotFormat() {
    return dot && isShowRuleKey && isShowTransitiveRuleKeys;
  }

  /**
   * @return set of paths passed to {@code --assume-modified-files} if either {@code
   *     --assume-modified-files} or {@code --assume-no-modified-files} is used, absent otherwise.
   */
  public ImmutableSet<Path> getTargetHashModifiedPaths() {
    return targetHashModifiedPaths.get().stream()
        .map(Paths::get)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    assertArguments();

    // Shortcut to parse the provided patterns using Graph Engine and display the result
    // All other parameters are effectively ignored
    if (isShowParseState) {
      outputParseStateWithGraphEngine(params);
      return ExitCode.SUCCESS;
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Targets", getConcurrencyLimit(params.getBuckConfig()))) {
      // Exit early if --resolve-alias is passed in: no need to parse any build files.
      if (isResolveAlias) {
        try (PerBuildState parserState =
            params
                .getParser()
                .getPerBuildStateFactory()
                .create(
                    createParsingContext(
                            params.getCells().getRootCell(), pool.getListeningExecutorService())
                        .withExcludeUnsupportedTargets(false)
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                    params.getParser().getPermState())) {
          ResolveAliasHelper.resolveAlias(params, parserState, arguments);
        }
        return ExitCode.SUCCESS;
      }

      return runWithExecutor(params, pool.getListeningExecutorService());
    } catch (BuildFileParseException | CycleException | VersionException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }
  }

  private void assertArguments() {
    // referencedFiles can try to find targets based on a file, so make sure at least
    // /something/ is provided. We don't want people accidentally crawling a whole repo
    // when they didn't intend to.
    if (arguments.isEmpty() && (this.referencedFiles.get().isEmpty() || isShowParseState)) {
      throw new CommandLineException(
          "Must specify at least one build target pattern. See https://buck.build/concept/build_target_pattern.html");
    }
  }

  // Graph Engine implementation of parsing the spec, serializing target nodes with dependencies,
  // and outputting it to console
  private void outputParseStateWithGraphEngine(CommandRunnerParams params) throws IOException {

    // Convert aliases to build target patterns
    ImmutableSet<String> targetPatterns =
        ImmutableSet.copyOf(AliasConfig.from(params.getBuckConfig()).resolveAliases(arguments));

    // Parse target patterns
    ImmutableSet<BuildTargetPattern> buildTargetPatterns =
        targetPatterns.stream()
            .map(
                (String pattern1) ->
                    BuildTargetPatternParser.parse(
                        pattern1, params.getCells().getRootCell().getCellNameResolver()))
            .collect(ImmutableSet.toImmutableSet());

    // Group all specs by cell
    // TODO: figure out how to group by with ImmutableMap
    Map<CanonicalCellName, List<BuildTargetPattern>> patternsPerCell =
        buildTargetPatterns.stream()
            .collect(
                Collectors.groupingBy(pattern -> pattern.getCellRelativeBasePath().getCellName()));

    // Build graph engines for each cell in provided specs and evaluate
    List<UnconfiguredTargetNodeWithDepsPackage> nodes = new ArrayList<>();
    Closer closer = Closer.create();
    try {
      // For each cell, build a Graph Engine which will parse that cell's targets
      // In the returned result, the key is a cell name and the value is Graph Engine instance
      // configured for this cell
      ImmutableMap<CanonicalCellName, GraphTransformationEngine> enginesPerCell =
          buildGraphEngineForEachCell(
              ImmutableSet.copyOf(patternsPerCell.keySet()), closer, params);

      // Execute graph engine for each cell sequentially
      // TODO(buck_team): parallelize it with Graph Engine
      for (Entry<CanonicalCellName, List<BuildTargetPattern>> cellAndPatterns :
          patternsPerCell.entrySet()) {
        GraphTransformationEngine engine = enginesPerCell.get(cellAndPatterns.getKey());

        ImmutableSet<
                ComposedKey<
                    BuildTargetPatternToBuildPackagePathKey, UnconfiguredTargetNodeWithDepsPackage>>
            keys =
                cellAndPatterns.getValue().stream()
                    .map(
                        pattern ->
                            ComposedKey.of(
                                BuildTargetPatternToBuildPackagePathKey.of(pattern),
                                UnconfiguredTargetNodeWithDepsPackage.class))
                    .collect(ImmutableSet.toImmutableSet());

        ImmutableMap<
                ComposedKey<
                    BuildTargetPatternToBuildPackagePathKey, UnconfiguredTargetNodeWithDepsPackage>,
                ComposedResult<
                    ComputeKey<UnconfiguredTargetNodeWithDepsPackage>,
                    UnconfiguredTargetNodeWithDepsPackage>>
            results = engine.computeAllUnchecked(keys);

        results.values().forEach(result -> nodes.addAll(result.resultMap().values()));
      }
    } catch (Throwable th) {
      // required by Closer to properly throw main exception along with suppressed ones
      closer.rethrow(th);
    } finally {
      closer.close();
    }

    // output
    try (JsonGenerator generator =
        ObjectMappers.createGenerator(params.getConsole().getStdOut()).useDefaultPrettyPrinter()) {
      ObjectMappers.WRITER.writeValue(generator, nodes);
    }
  }

  private ImmutableMap<CanonicalCellName, GraphTransformationEngine> buildGraphEngineForEachCell(
      ImmutableSet<CanonicalCellName> cellNames, Closer closer, CommandRunnerParams params) {
    ImmutableMap.Builder<CanonicalCellName, GraphTransformationEngine> builder =
        ImmutableMap.builderWithExpectedSize(cellNames.size());
    for (CanonicalCellName cellName : cellNames) {

      // Linear scan to find a cell that corresponds to the name. It is not supposed to have a lot
      // of cells so this is probably fine for now.
      // TODO(buck_team): add a method to resolve cell by name from the context
      Cell cell =
          params.getCells().getRootCell().getAllCells().stream()
              .filter(name -> name.getCanonicalName().equals(cellName))
              .findFirst()
              .orElseThrow(() -> new BuckUncheckedExecutionException("Unknown cell " + cellName));

      GraphTransformationEngine engine =
          GraphEngineFactory.create(params.getCells(), cell, closer, params);

      builder.put(cellName, engine);
    }
    return builder.build();
  }

  private ExitCode runWithExecutor(CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, CycleException,
          VersionException {
    if (isShowOutput) {
      CommandHelper.maybePrintShowOutputWarning(
          params.getBuckConfig().getView(CliConfig.class),
          params.getConsole().getAnsi(),
          params.getBuckEventBus());
    }
    Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses =
        getDescriptionClassFromParams(params);
    if (!descriptionClasses.isPresent()) {
      return ExitCode.FATAL_GENERIC;
    }

    // shortcut to old plain simple format
    if (!(isShowCellPath
        || isShowOutput
        || isShowOutputs
        || isShowFullOutput
        || isShowRuleKey
        || isShowTargetHash)) {
      printResults(
          params,
          executor,
          getMatchingNodes(
              params, buildTargetGraphAndTargets(params, executor), descriptionClasses));
      return ExitCode.SUCCESS;
    }

    // shortcut to DOT format, it only works along with rule keys and transitive rule keys
    // because we want to construct action graph
    if (shouldUseDotFormat()) {
      printDotFormat(params, executor);
      return ExitCode.SUCCESS;
    }

    ImmutableList<TargetNodeSpec> targetNodeSpecs = getTargetNodeSpecs(params);

    // plain or json output
    TargetGraphCreationResult targetGraphAndBuildTargetsForShowRules =
        buildTargetGraphAndTargetsForShowRules(
            params, targetNodeSpecs, executor, descriptionClasses);
    boolean useVersioning =
        isShowRuleKey || isShowOutput || isShowOutputs || isShowFullOutput
            ? params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()
            : params.getBuckConfig().getView(BuildBuckConfig.class).getTargetsVersions();
    targetGraphAndBuildTargetsForShowRules =
        useVersioning
            ? toVersionedTargetGraph(params, targetGraphAndBuildTargetsForShowRules)
            : targetGraphAndBuildTargetsForShowRules;
    ImmutableSortedMap<BuildTargetWithOutputs, TargetResult> showRulesResult =
        computeShowRules(
            params,
            targetNodeSpecs,
            executor,
            new Pair<>(
                targetGraphAndBuildTargetsForShowRules.getTargetGraph(),
                targetGraphAndBuildTargetsForShowRules
                    .getTargetGraph()
                    .getAll(targetGraphAndBuildTargetsForShowRules.getBuildTargets())));

    if (shouldUseJsonFormat()) {
      ImmutableSetMultimap.Builder<BuildTarget, OutputLabel> builder =
          ImmutableSetMultimap.builder();
      for (BuildTargetWithOutputs buildTargetWithOutputs : showRulesResult.keySet()) {
        builder.put(
            buildTargetWithOutputs.getBuildTarget(), buildTargetWithOutputs.getOutputLabel());
      }
      ImmutableSetMultimap<BuildTarget, OutputLabel> targetToAllLabels = builder.build();
      Iterable<TargetNode<?>> matchingNodes =
          targetGraphAndBuildTargetsForShowRules
              .getTargetGraph()
              .getAll(targetToAllLabels.keySet());
      printJsonForTargets(
          params,
          executor,
          matchingNodes,
          targetToAllLabels,
          showRulesResult,
          outputAttributes.get());
    } else {
      printShowRules(showRulesResult, params);
    }

    return ExitCode.SUCCESS;
  }

  /**
   * Output rules along with dependencies as a graph in DOT format As a part of invocation,
   * constructs both target and action graphs
   */
  private void printDotFormat(CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {
    TargetGraphCreationResult targetGraphAndTargets = buildTargetGraphAndTargets(params, executor);
    TargetGraph targetGraph = targetGraphAndTargets.getTargetGraph();
    ActionGraphAndBuilder result =
        params
            .getActionGraphProvider()
            .getActionGraph(TargetGraphCreationResult.of(targetGraph, ImmutableSet.of()));

    // construct real graph
    MutableDirectedGraph<BuildRule> actionGraphMutable = new MutableDirectedGraph<>();

    for (BuildRule rule : result.getActionGraph().getNodes()) {
      actionGraphMutable.addNode(rule);
      for (BuildRule node : rule.getBuildDeps()) {
        actionGraphMutable.addEdge(rule, node);
      }
    }

    try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
        getDefaultRuleKeyCacheScope(
            params,
            new RuleKeyCacheRecycler.SettingsAffectingCache(
                params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
                result.getActionGraph()))) {

      // ruleKeyFactory is used to calculate rule key that we also want to display on a graph
      DefaultRuleKeyFactory ruleKeyFactory =
          new DefaultRuleKeyFactory(
              new RuleKeyFieldLoader(params.getRuleKeyConfiguration()),
              params.getFileHashCache(),
              result.getActionGraphBuilder(),
              ruleKeyCacheScope.getCache(),
              Optional.empty());

      // it is time to construct DOT output
      Dot.builder(new DirectedAcyclicGraph<>(actionGraphMutable), "action_graph")
          .setNodeToName(
              node ->
                  node.getFullyQualifiedName()
                      + " "
                      + node.getType()
                      + " "
                      + ruleKeyFactory.build(node))
          .setNodeToTypeName(node -> node.getType())
          .build()
          .writeOutput(params.getConsole().getStdOut());
    }
  }

  private ImmutableList<TargetNodeSpec> getTargetNodeSpecs(CommandRunnerParams params) {
    if (arguments.isEmpty()) {
      return ImmutableList.of(
          TargetNodePredicateSpec.of(
              BuildFileSpec.fromRecursivePath(
                  CellRelativePath.of(
                      params.getCells().getRootCell().getCanonicalName(),
                      ForwardRelativePath.of("")))));
    }
    return parseArgumentsAsTargetNodeSpecs(
        params.getCells().getRootCell(),
        params.getClientWorkingDir(),
        arguments,
        params.getBuckConfig());
  }

  private TargetGraphCreationResult buildTargetGraphAndTargetsForShowRules(
      CommandRunnerParams params,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      ListeningExecutorService executor,
      Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses)
      throws InterruptedException, BuildFileParseException, IOException {
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    ParsingContext parsingContext =
        createParsingContext(params.getCells().getRootCell(), executor)
            .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode())
            .withSpeculativeParsing(SpeculativeParsing.ENABLED);
    if (arguments.isEmpty()) {
      TargetGraphCreationResult completeTargetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphWithTopLevelConfigurationTargets(
                  parsingContext, targetNodeSpecs, params.getTargetConfiguration());
      SortedMap<String, TargetNode<?>> matchingNodes =
          getMatchingNodes(params, completeTargetGraphAndBuildTargets, descriptionClasses);

      ImmutableSet<BuildTarget> buildTargets =
          ImmutableSet.copyOf(
              FluentIterable.from(matchingNodes.values()).transform(TargetNode::getBuildTarget));

      return TargetGraphCreationResult.of(
          completeTargetGraphAndBuildTargets.getTargetGraph(), buildTargets);
    } else {
      return filterTargetGraphCreationResultByType(
          params
              .getParser()
              .buildTargetGraphWithTopLevelConfigurationTargets(
                  parsingContext.withApplyDefaultFlavorsMode(
                      ParserConfig.ApplyDefaultFlavorsMode.DISABLED),
                  targetNodeSpecs,
                  params.getTargetConfiguration()),
          descriptionClasses);
    }
  }

  /**
   * Filters a TargetGraphCreationResult' build targets by description class. Each of the {@link
   * BuildTarget}s must exist in the {@link TargetGraph}
   *
   * @param targetGraphCreationResult The object to filter
   * @param descriptionClasses The classes to accept. If not present, or empty, all classes are
   *     accepted
   * @return The filtered TargetGraphCreationResult
   */
  private TargetGraphCreationResult filterTargetGraphCreationResultByType(
      TargetGraphCreationResult targetGraphCreationResult,
      Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses) {
    if (!descriptionClasses.isPresent() || descriptionClasses.get().isEmpty()) {
      return targetGraphCreationResult;
    }

    TargetGraph targetGraph = targetGraphCreationResult.getTargetGraph();
    return TargetGraphCreationResult.of(
        targetGraph,
        targetGraphCreationResult.getBuildTargets().stream()
            .filter(
                f ->
                    descriptionClasses
                        .get()
                        .contains(targetGraph.get(f).getDescription().getClass()))
            .collect(ImmutableSet.toImmutableSet()));
  }

  /** Print out matching targets in alphabetical order. */
  private void printResults(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      SortedMap<String, TargetNode<?>> matchingNodes)
      throws BuildFileParseException {
    if (shouldUseJsonFormat()) {
      printJsonForTargets(
          params,
          executor,
          matchingNodes.values(),
          ImmutableSetMultimap.of(),
          ImmutableMap.of(),
          outputAttributes.get());
    } else if (print0) {
      printTargets(matchingNodes.keySet(), "\0", params.getConsole().getStdOut());
    } else {
      printTargets(matchingNodes.keySet(), System.lineSeparator(), params.getConsole().getStdOut());
    }
  }

  private SortedMap<String, TargetNode<?>> getMatchingNodes(
      CommandRunnerParams params,
      TargetGraphCreationResult targetGraphAndBuildTargets,
      Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses)
      throws IOException {
    PathArguments.ReferencedFiles referencedFiles =
        getReferencedFiles(params.getCells().getRootCell().getFilesystem().getRootPath());
    SortedMap<String, TargetNode<?>> matchingNodes;
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
              isDetectTestChanges,
              parserConfig.getBuildFileName(),
              params.getCells().getRootCell().getFilesystem());
    }
    return matchingNodes;
  }

  private TargetGraphCreationResult buildTargetGraphAndTargets(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    ParsingContext parsingContext =
        createParsingContext(params.getCells().getRootCell(), executor)
            .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode())
            .withSpeculativeParsing(SpeculativeParsing.ENABLED);
    // Parse the entire action graph, or (if targets are specified), only the specified targets and
    // their dependencies. If we're detecting test changes we need the whole graph as tests are not
    // dependencies.
    TargetGraphCreationResult targetGraphCreationResult;
    if (arguments.isEmpty() || isDetectTestChanges) {
      targetGraphCreationResult =
          TargetGraphCreationResult.of(
              params
                  .getParser()
                  .buildTargetGraphWithTopLevelConfigurationTargets(
                      parsingContext,
                      ImmutableList.of(
                          TargetNodePredicateSpec.of(
                              BuildFileSpec.fromRecursivePath(
                                  CellRelativePath.of(
                                      params.getCells().getRootCell().getCanonicalName(),
                                      ForwardRelativePath.of(""))))),
                      params.getTargetConfiguration())
                  .getTargetGraph(),
              ImmutableSet.of());
    } else {
      targetGraphCreationResult =
          params
              .getParser()
              .buildTargetGraphWithTopLevelConfigurationTargets(
                  parsingContext,
                  parseArgumentsAsTargetNodeSpecs(
                      params.getCells().getRootCell(),
                      params.getClientWorkingDir(),
                      arguments,
                      params.getBuckConfig()),
                  params.getTargetConfiguration());
    }
    return params.getBuckConfig().getView(BuildBuckConfig.class).getTargetsVersions()
        ? toVersionedTargetGraph(params, targetGraphCreationResult)
        : targetGraphCreationResult;
  }

  @SuppressWarnings("unchecked")
  private Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> getDescriptionClassFromParams(
      CommandRunnerParams params) {
    ImmutableSet<String> types = getTypes();
    ImmutableSet.Builder<Class<? extends BaseDescription<?>>> descriptionClassesBuilder =
        ImmutableSet.builder();
    for (String name : types) {
      try {
        KnownRuleTypes knownRuleTypes =
            params.getKnownRuleTypesProvider().get(params.getCells().getRootCell());
        BaseDescription<?> description = knownRuleTypes.getDescriptorByName(name).getDescription();
        descriptionClassesBuilder.add((Class<? extends BaseDescription<?>>) description.getClass());
      } catch (IllegalArgumentException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe("Invalid build rule type: " + name));
        return Optional.empty();
      }
    }
    return Optional.of(descriptionClassesBuilder.build());
  }

  private void printShowRules(
      ImmutableSortedMap<BuildTargetWithOutputs, TargetResult> showRulesResult,
      CommandRunnerParams params) {
    for (Entry<BuildTargetWithOutputs, TargetResult> entry : showRulesResult.entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(entry.getKey().toString());
      TargetResult targetResult = entry.getValue();
      targetResult.getRuleKey().ifPresent(builder::add);
      if (isShowCellPath) {
        Path cellPath =
            params
                .getCells()
                .getRootCell()
                .getNewCellPathResolver()
                .getCellPath(entry.getKey().getBuildTarget().getCell());
        builder.add(cellPath.toString());
      }
      @Nullable
      ImmutableSet<String> outputPathForLabel =
          targetResult.getOutputPathsByLabels().get(entry.getKey().getOutputLabel());
      if (outputPathForLabel != null && !outputPathForLabel.isEmpty()) {
        // TODO(irenewchen): Should loop through the set of strings and construct one line per
        // String
        builder.add(Iterables.getOnlyElement(outputPathForLabel));
      }
      targetResult.getGeneratedSourcePath().ifPresent(builder::add);
      targetResult.getTargetHash().ifPresent(builder::add);
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
   * @param descriptionClasses If present, the result will be limited to targets with the specified
   *     types.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *     are testing.
   * @return A map of target names to target nodes.
   */
  @VisibleForTesting
  ImmutableSortedMap<String, TargetNode<?>> getMatchingNodes(
      TargetGraph graph,
      Optional<ImmutableSet<RelPath>> referencedFiles,
      Optional<ImmutableSet<BuildTarget>> matchingBuildTargets,
      Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses,
      boolean detectTestChanges,
      String buildFileName,
      ProjectFilesystem projectFilesystem) {
    ImmutableSet<TargetNode<?>> directOwners;
    if (referencedFiles.isPresent()) {
      BuildFileTree buildFileTree =
          new InMemoryBuildFileTree(
              graph.getNodes().stream()
                  .map(TargetNode::getBuildTarget)
                  .collect(ImmutableSet.toImmutableSet()),
              projectFilesystem);
      directOwners =
          graph.getNodes().stream()
              .filter(
                  new DirectOwnerPredicate(
                      buildFileTree, projectFilesystem, referencedFiles.get(), buildFileName))
              .collect(ImmutableSet.toImmutableSet());
    } else {
      directOwners = graph.getNodes();
    }
    Iterable<TargetNode<?>> selectedReferrers =
        FluentIterable.from(getDependentNodes(graph, directOwners, detectTestChanges))
            .filter(
                targetNode -> {
                  if (matchingBuildTargets.isPresent()
                      && !matchingBuildTargets.get().contains(targetNode.getBuildTarget())) {
                    return false;
                  }

                  return !descriptionClasses.isPresent()
                      || descriptionClasses.get().contains(targetNode.getDescription().getClass());
                });
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
   *     are testing.
   * @return A set of all nodes that transitively depend on {@code nodes} (a superset of {@code
   *     nodes}).
   */
  private static ImmutableSet<TargetNode<?>> getDependentNodes(
      TargetGraph graph, ImmutableSet<TargetNode<?>> nodes, boolean detectTestChanges) {
    ImmutableMultimap.Builder<TargetNode<?>, TargetNode<?>> extraEdgesBuilder =
        ImmutableMultimap.builder();

    if (detectTestChanges) {
      for (TargetNode<?> node : graph.getNodes()) {
        if (node.getConstructorArg() instanceof HasTests) {
          ImmutableSortedSet<BuildTarget> tests = ((HasTests) node.getConstructorArg()).getTests();
          for (BuildTarget testTarget : tests) {
            Optional<TargetNode<?>> testNode = graph.getOptional(testTarget);
            if (!testNode.isPresent()) {
              throw new HumanReadableException(
                  "'%s' (test of '%s') is not in the target graph.", testTarget, node);
            }
            extraEdgesBuilder.put(testNode.get(), node);
          }
        }
      }
    }
    ImmutableMultimap<TargetNode<?>, TargetNode<?>> extraEdges = extraEdgesBuilder.build();

    ImmutableSet.Builder<TargetNode<?>> builder = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<TargetNode<?>> traversal =
        new AbstractBreadthFirstTraversal<TargetNode<?>>(nodes) {
          @Override
          public Iterable<TargetNode<?>> visit(TargetNode<?> targetNode) {
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
      Iterable<TargetNode<?>> targetNodes,
      ImmutableSetMultimap<BuildTarget, OutputLabel> targetToAllLabels,
      ImmutableMap<BuildTargetWithOutputs, TargetResult> targetResults,
      ImmutableSet<String> outputAttributes)
      throws BuildFileParseException {
    PatternsMatcher attributesPatternsMatcher =
        outputAttributes.isEmpty() ? PatternsMatcher.ANY : new PatternsMatcher(outputAttributes);

    // Print the JSON representation of the build node for the specified target(s).
    params.getConsole().getStdOut().println("[");

    Iterator<TargetNode<?>> targetNodeIterator = targetNodes.iterator();

    try (PerBuildState state =
        new PerBuildStateFactory(
                params.getTypeCoercerFactory(),
                new DefaultConstructorArgMarshaller(),
                params.getKnownRuleTypesProvider(),
                new ParserPythonInterpreterProvider(
                    params.getCells().getRootCell().getBuckConfig(), params.getExecutableFinder()),
                params.getWatchman(),
                params.getBuckEventBus(),
                params.getUnconfiguredBuildTargetFactory(),
                params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
            .create(
                createParsingContext(params.getCells().getRootCell(), executor)
                    .withExcludeUnsupportedTargets(false),
                params.getParser().getPermState())) {

      JsonAttributeFormat jsonAttributeFormat =
          params.getBuckConfig().getView(CliConfig.class).getJsonAttributeFormat();

      while (targetNodeIterator.hasNext()) {
        TargetNode<?> targetNode = targetNodeIterator.next();
        @Nullable
        Map<String, Object> targetNodeAttributes =
            params
                .getParser()
                .getTargetNodeRawAttributes(
                    state,
                    params.getCells().getRootCell(),
                    targetNode,
                    DependencyStack.top(targetNode.getBuildTarget()));
        if (targetNodeAttributes == null) {
          printWarning(
              params,
              "unable to find rule for target "
                  + targetNode.getBuildTarget().getFullyQualifiedName());
          continue;
        }

        for (OutputLabel outputLabel : targetToAllLabels.get(targetNode.getBuildTarget())) {
          @Nullable
          TargetResult targetResult =
              targetResults.get(
                  BuildTargetWithOutputs.of(targetNode.getBuildTarget(), outputLabel));
          if (targetResult != null) {
            for (TargetResultFieldName field : TargetResultFieldName.values()) {
              Optional<?> fieldResult = field.getter.apply(targetResult);
              if (fieldResult.isPresent()) {
                targetNodeAttributes.put(field.name, fieldResult.get());
              }
            }
          }
        }

        targetNodeAttributes.put(
            "fully_qualified_name", targetNode.getBuildTarget().getFullyQualifiedName());
        if (isShowCellPath) {
          Path cellPath =
              params
                  .getCells()
                  .getRootCell()
                  .getNewCellPathResolver()
                  .getCellPath(targetNode.getBuildTarget().getCell());
          targetNodeAttributes.put("buck.cell_path", cellPath);
        }

        if (jsonAttributeFormat != JsonAttributeFormat.LEGACY) {
          targetNodeAttributes =
              targetNodeAttributes.entrySet().stream()
                  .collect(
                      ImmutableSortedMap.toImmutableSortedMap(
                          Comparator.naturalOrder(),
                          e -> jsonAttributeFormat.format(e.getKey()),
                          Entry::getValue));
        }

        // Print the build rule information as JSON.
        StringWriter stringWriter = new StringWriter();
        try {
          ObjectMappers.WRITER
              .withDefaultPrettyPrinter()
              .writeValue(
                  stringWriter,
                  attributesPatternsMatcher.filterMatchingMapKeys(targetNodeAttributes));
        } catch (IOException e) {
          // Shouldn't be possible while writing to a StringWriter...
          throw new RuntimeException(e);
        }
        params.getConsole().getStdOut().print(stringWriter.getBuffer().toString());
        if (targetNodeIterator.hasNext()) {
          params.getConsole().getStdOut().print(',');
        }
        params.getConsole().getStdOut().println();
      }
    }

    params.getConsole().getStdOut().println("]");
  }

  @VisibleForTesting
  static void printTargets(Iterable<String> targets, String newline, PrintStream printStream) {
    StringBuilder sb = new StringBuilder();
    for (String target : targets) {
      sb.append(target);
      sb.append(newline);
    }
    printStream.print(sb.toString());
    printStream.flush();
  }

  /**
   * Create a {@link ThriftRuleKeyLogger} depending on whether {@link TargetsCommand#ruleKeyLogPath}
   * is set or not
   */
  private Optional<ThriftRuleKeyLogger> createRuleKeyLogger() throws IOException {
    if (ruleKeyLogPath == null) {
      return Optional.empty();
    } else {
      return Optional.of(ThriftRuleKeyLogger.create(Paths.get(ruleKeyLogPath)));
    }
  }

  /**
   * Assumes at least one target is specified. Computes each of the specified targets, followed by
   * the rule key, output path, and/or target hash, depending on what flags are passed in.
   *
   * @return An immutable map consisting of result of show options for to each target rule
   */
  private ImmutableSortedMap<BuildTargetWithOutputs, TargetResult> computeShowRules(
      CommandRunnerParams params,
      ImmutableList<TargetNodeSpec> targetNodeSpecs,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndTargetNodes)
      throws IOException, InterruptedException, BuildFileParseException, CycleException {

    TargetResultBuilders targetResultBuilders = new TargetResultBuilders();
    if (isShowTargetHash) {
      // If we need to get transitive dependencies, then make sure we populate targetResultBuilders
      // with /all/ recursive parse time dependencies
      Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndMaybeRecursiveTargetNodes =
          targetGraphAndTargetNodes;
      if (isShowTransitiveTargetHashes) {
        targetGraphAndMaybeRecursiveTargetNodes =
            new Pair<>(
                targetGraphAndTargetNodes.getFirst(),
                getTransitiveParseTimeDeps(targetGraphAndTargetNodes));
      }
      computeShowTargetHash(
          params, executor, targetGraphAndMaybeRecursiveTargetNodes, targetResultBuilders);
    }

    // We only need the action graph if we're showing the output or the keys, and the
    // RuleKeyFactory if we're showing the keys.
    Optional<ActionGraph> actionGraph;
    Optional<ActionGraphBuilder> graphBuilder;
    Optional<ParallelRuleKeyCalculator<RuleKey>> ruleKeyCalculator = Optional.empty();

    try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
      if (isShowRuleKey || isShowOutput || isShowOutputs || isShowFullOutput) {
        ActionGraphAndBuilder result =
            params
                .getActionGraphProvider()
                .getActionGraph(
                    TargetGraphCreationResult.of(
                        targetGraphAndTargetNodes.getFirst(), ImmutableSet.of()));
        actionGraph = Optional.of(result.getActionGraph());
        graphBuilder = Optional.of(result.getActionGraphBuilder());
        if (isShowRuleKey) {
          try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
              getDefaultRuleKeyCacheScope(
                  params,
                  new RuleKeyCacheRecycler.SettingsAffectingCache(
                      params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
                      result.getActionGraph()))) {

            // Setup a parallel rule key calculator to use when building rule keys.
            ruleKeyCalculator =
                Optional.of(
                    new ParallelRuleKeyCalculator<>(
                        executor,
                        new DefaultRuleKeyFactory(
                            new RuleKeyFieldLoader(params.getRuleKeyConfiguration()),
                            params.getFileHashCache(),
                            result.getActionGraphBuilder(),
                            ruleKeyCacheScope.getCache(),
                            Optional.ofNullable(ruleKeyLogger)),
                        new DefaultRuleDepsCache(
                            graphBuilder.get(), result.getBuildEngineActionToBuildRuleResolver()),
                        (eventBus, rule) -> () -> {}));
          }
        }
      } else {
        actionGraph = Optional.empty();
        graphBuilder = Optional.empty();
      }

      // Start rule calculations in parallel.
      if (actionGraph.isPresent() && isShowRuleKey) {
        for (TargetNode<?> targetNode : targetGraphAndTargetNodes.getSecond()) {
          if (!targetNode.getRuleType().isBuildRule()) {
            continue;
          }
          BuildRule rule = graphBuilder.get().requireRule(targetNode.getBuildTarget());
          ruleKeyCalculator.get().calculate(params.getBuckEventBus(), rule);
        }
      }

      // TODO rewrite targets so that this doesn't alter the ActionGraph
      for (TargetNode<?> targetNode : targetGraphAndTargetNodes.getSecond()) {
        ImmutableTargetResult.Builder builder =
            targetResultBuilders.getOrCreate(targetNode.getBuildTarget());
        Objects.requireNonNull(builder);
        if (actionGraph.isPresent() && isShowRuleKey) {
          if (!targetNode.getRuleType().isBuildRule()) {
            continue;
          }
          BuildRule rule = graphBuilder.get().requireRule(targetNode.getBuildTarget());
          builder.setRuleKey(
              Futures.getUnchecked(
                      ruleKeyCalculator.get().calculate(params.getBuckEventBus(), rule))
                  .toString());
          if (isShowTransitiveRuleKeys) {
            ParallelRuleKeyCalculator<RuleKey> calculator = ruleKeyCalculator.get();
            AbstractBreadthFirstTraversal.traverse(
                rule,
                traversedRule -> {
                  ImmutableTargetResult.Builder showOptionsBuilder =
                      targetResultBuilders.getOrCreate(traversedRule.getBuildTarget());
                  showOptionsBuilder.setRuleKey(
                      Futures.getUnchecked(
                              calculator.calculate(params.getBuckEventBus(), traversedRule))
                          .toString());
                  return traversedRule.getBuildDeps();
                });
          }
        }
      }

      ImmutableSet<BuildTargetWithOutputs> buildTargetsWithOutputs =
          matchBuildTargetsWithLabelsFromSpecs(targetNodeSpecs, targetResultBuilders.map.keySet());
      TargetGraph targetGraph = targetGraphAndTargetNodes.getFirst();
      graphBuilder.ifPresent(
          actionGraphBuilder ->
              processBuildRules(
                  targetResultBuilders.map,
                  targetGraph,
                  buildTargetsWithOutputs,
                  actionGraphBuilder,
                  params));

      ImmutableSortedMap.Builder<BuildTargetWithOutputs, TargetResult> builder =
          ImmutableSortedMap.naturalOrder();
      buildTargetsWithOutputs.forEach(
          targetWithOutputs ->
              builder.put(
                  targetWithOutputs,
                  Objects.requireNonNull(
                          targetResultBuilders.map.get(targetWithOutputs.getBuildTarget()))
                      .build()));
      return builder.build();
    }
  }

  private void processBuildRules(
      Map<BuildTarget, ImmutableTargetResult.Builder> buildTargetToTargetBuilderMap,
      TargetGraph targetGraph,
      ImmutableSet<BuildTargetWithOutputs> buildTargetsWithOutputs,
      ActionGraphBuilder graphBuilder,
      CommandRunnerParams params) {
    for (BuildTargetWithOutputs targetWithOutputs : buildTargetsWithOutputs) {
      BuildTarget target = targetWithOutputs.getBuildTarget();
      if (!targetGraph.get(target).getRuleType().isBuildRule()) {
        continue;
      }
      ImmutableTargetResult.Builder builder =
          Objects.requireNonNull(buildTargetToTargetBuilderMap.get(target));
      BuildRule rule = graphBuilder.requireRule(target);
      builder.setRuleType(rule.getType());
      if (isShowOutput || isShowOutputs || isShowFullOutput) {
        SourcePathResolverAdapter sourcePathResolverAdapter = graphBuilder.getSourcePathResolver();
        PathUtils.getUserFacingOutputPath(
                sourcePathResolverAdapter,
                rule,
                params.getBuckConfig().getView(BuildBuckConfig.class).getBuckOutCompatLink(),
                targetWithOutputs.getOutputLabel(),
                isShowOutputs)
            .map(path -> pathToString(path, params))
            .ifPresent(
                path -> {
                  // Need to call both #setOutputPath and #putOutputPathsByLabels because
                  // buck.outputPath for JSON printing uses getOutputPath. This can be removed after
                  // client usages stop parsing buck.outputPath
                  if (targetWithOutputs.getOutputLabel().isDefault()) {
                    builder.setOutputPath(path);
                  }
                  builder.putOutputPathsByLabels(
                      targetWithOutputs.getOutputLabel(), ImmutableSet.of(path));
                });
        // If the output dir is requested, also calculate the generated src dir
        if (rule instanceof JavaLibrary) {
          ((JavaLibrary) rule)
              .getGeneratedAnnotationSourcePath()
              .map(sourcePathResolverAdapter::getRelativePath)
              .map(rule.getProjectFilesystem()::resolve)
              .map(path -> pathToString(path, params))
              .ifPresent(builder::setGeneratedSourcePath);
        }
      }
    }
  }

  private String pathToString(Path path, CommandRunnerParams params) {
    Path formattedPath =
        isShowFullOutput
            ? path
            : params.getCells().getRootCell().getFilesystem().relativize(path).getPath();
    return formattedPath.toString();
  }

  private Pair<TargetGraph, Iterable<TargetNode<?>>> computeTargetsAndGraphToShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndTargetNodes)
      throws InterruptedException, BuildFileParseException, IOException {

    if (isDetectTestChanges) {
      ImmutableSet<BuildTarget> explicitTestTargets =
          TargetNodes.getTestTargetsForNodes(
              targetGraphAndTargetNodes
                  .getFirst()
                  .getSubgraph(targetGraphAndTargetNodes.getSecond())
                  .getNodes()
                  .iterator());
      LOG.debug("Got explicit test targets: %s", explicitTestTargets);

      ImmutableSet<BuildTarget> matchingBuildTargetsWithTests =
          mergeBuildTargets(targetGraphAndTargetNodes.getSecond(), explicitTestTargets);

      // Parse the BUCK files for the tests of the targets passed in from the command line.
      TargetGraph targetGraphWithTests =
          params
              .getParser()
              .buildTargetGraph(
                  createParsingContext(params.getCells().getRootCell(), executor)
                      .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                      .withExcludeUnsupportedTargets(false),
                  matchingBuildTargetsWithTests)
              .getTargetGraph();

      return new Pair<>(
          targetGraphWithTests, targetGraphWithTests.getAll(matchingBuildTargetsWithTests));
    } else {
      return targetGraphAndTargetNodes;
    }
  }

  private ImmutableSet<BuildTarget> mergeBuildTargets(
      Iterable<TargetNode<?>> targetNodes, Iterable<BuildTarget> buildTargets) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();

    targetsBuilder.addAll(buildTargets);

    for (TargetNode<?> node : targetNodes) {
      targetsBuilder.add(node.getBuildTarget());
    }
    return targetsBuilder.build();
  }

  /**
   * Get the set of TargetNodes and their parse time dependencies
   *
   * @param targetGraphAndTargetNodes The target graph, and the leaf nodes where the traversal will
   *     start
   * @return An iterable of the original leaf nodes and all of their recursive parse time
   *     dependencies
   * @throws CycleException There's a cycle in the graph
   */
  private Iterable<TargetNode<?>> getTransitiveParseTimeDeps(
      Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndTargetNodes) throws CycleException {
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(
            node -> targetGraphAndTargetNodes.getFirst().getAll(node.getParseDeps()).iterator());
    return traversal.traverse(targetGraphAndTargetNodes.getSecond());
  }

  private FileHashLoader createOrGetFileHashLoader(CommandRunnerParams params) throws IOException {
    switch (targetHashFileMode) {
      case PATHS_AND_CONTENTS:
        return params.getFileHashCache();
      case PATHS_ONLY:
        return new FilePathHashLoader(
            params.getCells().getRootCell().getRoot().getPath(),
            getTargetHashModifiedPaths(),
            params
                    .getCells()
                    .getRootCell()
                    .getBuckConfig()
                    .getView(ParserConfig.class)
                    .getAllowSymlinks()
                != ParserConfig.AllowSymlinks.FORBID);
    }
    throw new IllegalStateException(
        "Invalid value for target hash file mode: " + targetHashFileMode);
  }

  private void computeShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndTargetNodes,
      TargetResultBuilders resultBuilders)
      throws IOException, InterruptedException, BuildFileParseException, CycleException {
    LOG.debug("Getting target hash for %s", targetGraphAndTargetNodes.getSecond());

    Pair<TargetGraph, Iterable<TargetNode<?>>> targetGraphAndNodesWithTests =
        computeTargetsAndGraphToShowTargetHash(params, executor, targetGraphAndTargetNodes);

    TargetGraph targetGraphWithTests = targetGraphAndNodesWithTests.getFirst();

    FileHashLoader fileHashLoader = createOrGetFileHashLoader(params);

    // Hash each target's rule description and contents of any files.
    ImmutableMap<BuildTarget, HashCode> buildTargetHashes;

    try (PerBuildState state =
        new PerBuildStateFactory(
                params.getTypeCoercerFactory(),
                new DefaultConstructorArgMarshaller(),
                params.getKnownRuleTypesProvider(),
                new ParserPythonInterpreterProvider(
                    params.getCells().getRootCell().getBuckConfig(), params.getExecutableFinder()),
                params.getWatchman(),
                params.getBuckEventBus(),
                params.getUnconfiguredBuildTargetFactory(),
                params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
            .create(
                createParsingContext(params.getCells().getRootCell(), executor)
                    .withExcludeUnsupportedTargets(false),
                params.getParser().getPermState())) {
      buildTargetHashes =
          new TargetGraphHashing(
                  params.getBuckEventBus(),
                  targetGraphWithTests,
                  fileHashLoader,
                  targetGraphAndNodesWithTests.getSecond(),
                  executor,
                  params.getRuleKeyConfiguration(),
                  node ->
                      params
                          .getParser()
                          .getTargetNodeRawAttributesJob(
                              state,
                              params.getCells().getRootCell(),
                              node,
                              DependencyStack.top(node.getBuildTarget())),
                  getHashFunction())
              .hashTargetGraph();
    }

    ImmutableMap<BuildTarget, HashCode> finalHashes =
        rehashWithTestsIfNeeded(
            targetGraphWithTests, targetGraphAndTargetNodes.getSecond(), buildTargetHashes);

    for (TargetNode<?> targetNode : targetGraphAndTargetNodes.getSecond()) {
      BuildTarget buildTarget = targetNode.getBuildTarget();
      resultBuilders
          .getOrCreate(buildTarget)
          .setTargetHash(getHashCodeOrThrow(finalHashes, buildTarget).toString());
    }
  }

  private ImmutableMap<BuildTarget, HashCode> rehashWithTestsIfNeeded(
      TargetGraph targetGraphWithTests,
      Iterable<TargetNode<?>> inputTargets,
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes)
      throws CycleException {

    if (!isDetectTestChanges) {
      return buildTargetHashes;
    }

    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(
            node -> targetGraphWithTests.getAll(node.getParseDeps()).iterator());

    Map<BuildTarget, HashCode> hashesWithTests = new HashMap<>();

    for (TargetNode<?> node : traversal.traverse(inputTargets)) {
      hashNodeWithDependencies(buildTargetHashes, hashesWithTests, node);
    }

    return ImmutableMap.copyOf(hashesWithTests);
  }

  private void hashNodeWithDependencies(
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes,
      Map<BuildTarget, HashCode> hashesWithTests,
      TargetNode<?> node) {
    HashCode nodeHashCode = getHashCodeOrThrow(buildTargetHashes, node.getBuildTarget());

    Hasher hasher = getHashFunction().newHasher();
    hasher.putBytes(nodeHashCode.asBytes());

    Iterable<BuildTarget> dependentTargets = node.getParseDeps();
    LOG.debug("Hashing target %s with dependent nodes %s", node, dependentTargets);
    for (BuildTarget targetToHash : dependentTargets) {
      HashCode dependencyHash = getHashCodeOrThrow(hashesWithTests, targetToHash);
      hasher.putBytes(dependencyHash.asBytes());
    }

    if (isDetectTestChanges) {
      for (BuildTarget targetToHash :
          Objects.requireNonNull(TargetNodes.getTestTargetsForNode(node))) {
        HashCode testNodeHashCode = getHashCodeOrThrow(buildTargetHashes, targetToHash);
        hasher.putBytes(testNodeHashCode.asBytes());
      }
    }
    hashesWithTests.put(node.getBuildTarget(), hasher.hash());
  }

  private HashFunction getHashFunction() {
    switch (targetHashFunction) {
      case SHA1:
        return Hashing.sha1();
      case MURMUR_HASH3:
        return Hashing.murmur3_128();
    }
    throw new UnsupportedOperationException();
  }

  private static HashCode getHashCodeOrThrow(
      Map<BuildTarget, HashCode> buildTargetHashCodes, BuildTarget buildTarget) {
    HashCode hashCode = buildTargetHashCodes.get(buildTarget);
    return Preconditions.checkNotNull(hashCode, "Cannot find hash for target: %s", buildTarget);
  }

  private static class DirectOwnerPredicate implements Predicate<TargetNode<?>> {

    private final ProjectFilesystem projectFilesystem;
    private final ImmutableSet<RelPath> referencedInputs;
    private final ImmutableSet<RelPath> basePathOfTargets;
    private final String buildFileName;

    /**
     * @param referencedInputs A {@link TargetNode} must reference at least one of these paths as
     *     input to match the predicate. All the paths must be relative to the project root. Ignored
     */
    public DirectOwnerPredicate(
        BuildFileTree buildFileTree,
        ProjectFilesystem projectFilesystem,
        ImmutableSet<RelPath> referencedInputs,
        String buildFileName) {
      this.projectFilesystem = projectFilesystem;
      this.referencedInputs = referencedInputs;

      ImmutableSet.Builder<RelPath> basePathOfTargetsBuilder = ImmutableSet.builder();
      for (RelPath input : referencedInputs) {
        buildFileTree.getBasePathOfAncestorTarget(input).ifPresent(basePathOfTargetsBuilder::add);
      }
      this.basePathOfTargets = basePathOfTargetsBuilder.build();
      this.buildFileName = buildFileName;
    }

    @Override
    public boolean test(TargetNode<?> node) {
      // For any referenced file, only those with the nearest target base path can
      // directly depend on that file.
      if (!basePathOfTargets.contains(
          node.getBuildTarget()
              .getCellRelativeBasePath()
              .getPath()
              .toRelPath(projectFilesystem.getFileSystem()))) {
        return false;
      }

      for (ForwardRelativePath input : node.getInputs()) {
        for (RelPath referencedInput : referencedInputs) {
          if (referencedInput.startsWith(input.toPath(projectFilesystem.getFileSystem()))) {
            return true;
          }
        }
      }

      return referencedInputs.contains(
          RelPath.of(
              node.getBuildTarget()
                  .getCellRelativeBasePath()
                  .getPath()
                  .toRelPath(projectFilesystem.getFileSystem())
                  .resolve(buildFileName)));
    }
  }

  /**
   * Holds the output values for a build target. Handlers for different output types will accumulate
   * their results in to result builder for each build target.
   */
  private static class TargetResultBuilders {

    final Map<BuildTarget, ImmutableTargetResult.Builder> map = new HashMap<>();

    ImmutableTargetResult.Builder getOrCreate(BuildTarget target) {
      return map.computeIfAbsent(target, ignored -> ImmutableTargetResult.builder());
    }
  }

  @BuckStyleValueWithBuilder
  abstract static class TargetResult {

    public abstract Optional<String> getOutputPath();

    public abstract ImmutableMap<OutputLabel, ImmutableSet<String>> getOutputPathsByLabels();

    public abstract Optional<String> getGeneratedSourcePath();

    public abstract Optional<String> getRuleKey();

    public abstract Optional<String> getTargetHash();

    public abstract Optional<String> getRuleType();

    /**
     * Wrapper method for {@link #getOutputPathsByLabels} to make the return type {@link Optional}
     * so empty lists and empty maps won't be printed in the JSON output. {@link
     * #getOutputPathsByLabels} isn't Optional to make it easy to interact with while building (i.e.
     * to have access to #putOutputPathsByLabels from codegen).
     */
    public Optional<ImmutableMap<OutputLabel, ImmutableSet<String>>> getOutputPaths() {
      ImmutableMap<OutputLabel, ImmutableSet<String>> result = getOutputPathsByLabels();
      return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }
  }

  private enum TargetResultFieldName {
    OUTPUT_PATH("buck.outputPath", TargetResult::getOutputPath),
    GEN_SRC_PATH("buck.generatedSourcePath", TargetResult::getGeneratedSourcePath),
    TARGET_HASH("buck.targetHash", TargetResult::getTargetHash),
    RULE_KEY("buck.ruleKey", TargetResult::getRuleKey),
    RULE_TYPE("buck.ruleType", TargetResult::getRuleType),
    OUTPUT_PATHS("buck.outputPaths", TargetResult::getOutputPaths);

    private String name;
    private Function<TargetResult, Optional<?>> getter;

    TargetResultFieldName(String name, Function<TargetResult, Optional<?>> getter) {
      this.name = name;
      this.getter = getter;
    }
  }
}
