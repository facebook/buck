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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.MergedTargetGraph;
import com.facebook.buck.core.model.targetgraph.MergedTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.InternalTargetAttributeNames;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryNormalizer;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FileOptionHandler;

/** Provides base functionality for query commands. */
public abstract class AbstractQueryCommand extends AbstractCommand {
  private static final Logger LOG = Logger.get(AbstractCommand.class);

  /**
   * Example usage:
   *
   * <pre>
   * buck query "allpaths('//path/to:target', '//path/to:other')" --output-format dot --output-file /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Deprecated
  @Option(
      name = "--dot",
      usage = "Deprecated (use `--output-format dot`): Print result as Dot graph",
      forbids = {"--json", "--output-format"})
  private boolean generateDotOutput;

  @Deprecated
  @Option(
      name = "--bfs",
      usage = "Deprecated (use `--output-format dot_bfs`): Sort the dot output in bfs order",
      depends = {"--dot"})
  private boolean generateBFSOutput;

  @Deprecated
  @Option(
      name = "--json",
      usage = "Deprecated (use `--output-format json`): Output in JSON format",
      forbids = {"--dot", "--output-format"})
  protected boolean generateJsonOutput;

  /** Enum with values for `--output-format` CLI parameter */
  protected enum OutputFormat {
    /** Format output as list */
    LIST,

    /** Format output as dot graph */
    DOT,

    /** Format output as dot graph, in a more compact format */
    DOT_COMPACT,

    /** Format output as dot graph in bfs order */
    DOT_BFS,

    /** Format output as dot graph in bfs order, in a more compact format */
    DOT_BFS_COMPACT,

    /** Format output as JSON */
    JSON,

    /** Format output as Thrift binary */
    THRIFT,
  }

  @Option(
      name = "--output-format",
      usage =
          "Output format (default: list).\n"
              + " dot -  dot graph format.\n"
              + " dot_compact - dot graph format, compacted.\n"
              + " dot_bfs -  dot graph format in bfs order.\n"
              + " dot_bfs_compact - dot graph format in bfs order, compacted.\n"
              + " json - JSON format.\n"
              + " json_unconfigured - JSON format with unevaluated selects\n"
              + " thrift - thrift binary format.\n")
  protected OutputFormat outputFormat = OutputFormat.LIST;

  @Option(
      name = "--output-file",
      usage = "Specify output file path for a result",
      handler = FileOptionHandler.class)
  @Nullable
  private File outputFile;

  /** Sort Output format. */
  public enum SortOutputFormat {
    LABEL,
    /** Rank by the length of the shortest path from a root node. */
    MINRANK,
    /** Rank by the length of the longest path from a root node. */
    MAXRANK;

    boolean needToSortByRank() {
      return this == MAXRANK || this == MINRANK;
    }
  }

  @Option(
      name = "--sort-output",
      // leaving `output` for a backward compatibility with existing code console parameters
      aliases = {"--output"},
      usage =
          "Sort output format (default: label). "
              + "minrank/maxrank: Sort the output in rank order and output the ranks "
              + "according to the length of the shortest or longest path from a root node, "
              + "respectively. This does not apply to --output-format equals to dot, dot_bfs, json and thrift.")
  private QueryCommand.SortOutputFormat sortOutputFormat = QueryCommand.SortOutputFormat.LABEL;

  @Deprecated
  @Option(
      name = "--output-attributes",
      usage =
          "Deprecated: List of attributes to output, --output-attributes attr1 att2 ... attrN. "
              + "Attributes can be regular expressions. The preferred replacement is "
              + "--output-attribute.",
      handler = StringSetOptionHandler.class,
      forbids = {"--output-attribute"})
  private Supplier<ImmutableSet<String>> outputAttributesDeprecated =
      Suppliers.ofInstance(ImmutableSet.of());

  // Two options are kept to not break the UI and scripts
  @Option(
      name = "--output-attribute",
      usage =
          "List of attributes to output, --output-attributes attr1. Attributes can be "
              + "regular expressions. Multiple attributes may be selected by specifying this option "
              + "multiple times.",
      handler = SingleStringSetOptionHandler.class,
      forbids = {"--output-attributes"})
  @VisibleForTesting
  Supplier<ImmutableSet<String>> outputAttributesSane = Suppliers.ofInstance(ImmutableSet.of());

  /** Which of *query commands was invoked */
  protected enum WhichQueryCommand {
    QUERY,
    UQUERY,
  }

  protected abstract WhichQueryCommand whichQueryCommand();

  private ImmutableSet<String> outputAttributes() {
    // There's no easy way apparently to ensure that an option has not been set
    ImmutableSet<String> deprecated = outputAttributesDeprecated.get();
    ImmutableSet<String> sane = outputAttributesSane.get();
    return sane.size() > deprecated.size() ? sane : deprecated;
  }

  protected boolean shouldOutputAttributes() {
    return !outputAttributes().isEmpty();
  }

  @Argument(handler = QueryMultiSetOptionHandler.class)
  protected List<String> arguments = new ArrayList<>();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @VisibleForTesting
  void formatAndRunQuery(CommandRunnerParams params, BuckQueryEnvironment env)
      throws IOException, InterruptedException, QueryException {

    if (generateJsonOutput) {
      outputFormat = OutputFormat.JSON;
    } else if (generateDotOutput) {
      outputFormat = generateBFSOutput ? OutputFormat.DOT_BFS : OutputFormat.DOT;
    }

    String queryFormat = arguments.get(0);
    List<String> formatArgs = arguments.subList(1, arguments.size());
    if (queryFormat.contains(QueryNormalizer.SET_SUBSTITUTOR)) {
      runSingleQuery(params, env, QueryNormalizer.normalizePattern(queryFormat, formatArgs));
      return;
    }
    if (queryFormat.contains("%s")) {
      try (CloseableWrapper<PrintStream> printStreamWrapper = getPrintStreamWrapper(params)) {
        runMultipleQuery(
            params,
            env,
            queryFormat,
            formatArgs,
            // generateJsonOutput is deprecated and have to be set as outputFormat parameter
            outputFormat == OutputFormat.JSON,
            outputAttributes(),
            printStreamWrapper.get(),
            whichQueryCommand());
      }
      return;
    }
    if (formatArgs.size() > 0) {
      throw new CommandLineException(
          "Must not specify format arguments without a %s or %Ss in the query");
    }
    runSingleQuery(params, env, queryFormat);
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage: buck query <query format>
   * <input1> <input2> <...> <inputN>
   */
  static void runMultipleQuery(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      String queryFormat,
      List<String> inputsFormattedAsBuildTargets,
      boolean generateJsonOutput,
      ImmutableSet<String> attributesFilter,
      PrintStream printStream,
      WhichQueryCommand whichQueryCommand)
      throws IOException, InterruptedException, QueryException {
    if (inputsFormattedAsBuildTargets.isEmpty()) {
      throw new CommandLineException(
          "specify one or more input targets after the query expression format");
    }

    // Do an initial pass over the query arguments and parse them into their expressions so we can
    // preload all the target patterns from every argument in one go, as doing them one-by-one is
    // really inefficient.
    Set<String> targetLiterals = new LinkedHashSet<>();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      QueryExpression<QueryBuildTarget> expr = QueryExpression.parse(query, env);
      expr.collectTargetPatterns(targetLiterals);
    }
    env.preloadTargetPatterns(targetLiterals);

    // Now execute the query on the arguments one-by-one.
    TreeMultimap<String, QueryTarget> queryResultMap =
        TreeMultimap.create(String::compareTo, QueryTarget::compare);
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      Set<QueryTarget> queryResult = env.evaluateQuery(query);
      queryResultMap.putAll(input, queryResult);
    }

    LOG.debug("Printing out %d targets", queryResultMap.size());

    if (attributesFilter.size() > 0) {
      collectAndPrintAttributesAsJson(
          params,
          env,
          queryResultMap.asMap().values().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet()),
          attributesFilter,
          printStream,
          whichQueryCommand);
    } else if (generateJsonOutput) {
      CommandHelper.printJsonOutput(queryResultMap, printStream);
    } else {
      CommandHelper.print(queryResultMap, printStream);
    }
  }

  private void runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env, String query)
      throws IOException, InterruptedException, QueryException {
    Set<QueryTarget> queryResult = env.evaluateQuery(query);
    LOG.debug("Printing out %d targets", queryResult.size());

    try (CloseableWrapper<PrintStream> printStreamWrapper = getPrintStreamWrapper(params)) {
      PrintStream printStream = printStreamWrapper.get();

      if (sortOutputFormat.needToSortByRank()) {
        printRankOutput(params, env, asQueryBuildTargets(queryResult), printStream);
        return;
      }

      switch (outputFormat) {
        case DOT:
        case DOT_COMPACT:
          printDotOutput(
              params,
              env,
              asQueryBuildTargets(queryResult),
              Dot.OutputOrder.SORTED,
              printStream,
              outputFormat == OutputFormat.DOT_COMPACT);
          break;

        case DOT_BFS:
        case DOT_BFS_COMPACT:
          printDotOutput(
              params,
              env,
              asQueryBuildTargets(queryResult),
              Dot.OutputOrder.BFS,
              printStream,
              outputFormat == OutputFormat.DOT_BFS_COMPACT);
          break;

        case JSON:
          printJsonOutputConfiguredOrUnconfigured(params, env, queryResult, printStream);
          break;

        case THRIFT:
          printThriftOutput(params, env, asQueryBuildTargets(queryResult), printStream);
          break;

        case LIST:
        default:
          printListOutput(params, env, queryResult, printStream);
      }
    }
  }

  /** @return set as {@link QueryBuildTarget}s or throw {@link IllegalArgumentException} */
  @SuppressWarnings("unchecked")
  public static Set<QueryBuildTarget> asQueryBuildTargets(Set<? extends QueryTarget> set) {
    // It is probably rare that there is a QueryTarget that is not a QueryBuildTarget.
    boolean hasInvalidItem = set.stream().anyMatch(item -> !(item instanceof QueryBuildTarget));
    if (hasInvalidItem) {
      throw new IllegalArgumentException(
          String.format("%s has elements that are not QueryBuildTarget", set));
    }
    return (Set<QueryBuildTarget>) set;
  }

  private void printJsonOutputConfiguredOrUnconfigured(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    if (whichQueryCommand() == WhichQueryCommand.UQUERY) {
      printJsonUnconfiguredOutput(params, env, queryResult, printStream);
    } else {
      printJsonOutput(params, env, queryResult, printStream);
    }
  }

  private void printJsonOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws IOException, QueryException {
    if (shouldOutputAttributes()) {
      collectAndPrintAttributesAsJson(
          params, env, queryResult, outputAttributes(), printStream, whichQueryCommand());
    } else {
      CommandHelper.printJsonOutput(queryResult, printStream);
    }
  }

  private void printListOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    if (shouldOutputAttributes()) {
      printJsonOutputConfiguredOrUnconfigured(params, env, queryResult, printStream);
    } else {
      CommandHelper.print(queryResult, printStream);
    }
  }

  private void printDotOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      Dot.OutputOrder outputOrder,
      PrintStream printStream,
      boolean compactMode)
      throws IOException, QueryException {
    MergedTargetGraph mergedTargetGraph = MergedTargetGraph.merge(env.getTargetGraph());

    ImmutableSet<TargetNode<?>> nodesFromQueryTargets = env.getNodesFromQueryTargets(queryResult);
    ImmutableSet<UnflavoredBuildTarget> targetsFromQueryTargets =
        nodesFromQueryTargets.stream()
            .map(t -> t.getBuildTarget().getUnflavoredBuildTarget())
            .collect(ImmutableSet.toImmutableSet());

    Dot.Builder<MergedTargetNode> dotBuilder =
        Dot.builder(mergedTargetGraph, "result_graph")
            .setNodesToFilter(n -> targetsFromQueryTargets.contains(n.getBuildTarget()))
            .setNodeToName(targetNode -> targetNode.getBuildTarget().getFullyQualifiedName())
            .setNodeToTypeName(targetNode -> targetNode.getRuleType().getName())
            .setOutputOrder(outputOrder)
            .setCompactMode(compactMode);
    if (shouldOutputAttributes()) {
      Function<MergedTargetNode, ImmutableSortedMap<String, String>> nodeToAttributes =
          getNodeToAttributeFunction(params, env);
      dotBuilder.setNodeToAttributes(nodeToAttributes);
    }
    dotBuilder.build().writeOutput(printStream);
  }

  @Nonnull
  private Function<MergedTargetNode, ImmutableSortedMap<String, String>> getNodeToAttributeFunction(
      CommandRunnerParams params, BuckQueryEnvironment env) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    return node ->
        getAttributes(
                params,
                env,
                patternsMatcher,
                node,
                DependencyStack.top(node.getBuildTarget()),
                whichQueryCommand())
            .map(
                attrs ->
                    attrs.entrySet().stream()
                        .collect(
                            ImmutableSortedMap.toImmutableSortedMap(
                                Comparator.naturalOrder(),
                                e -> e.getKey(),
                                e -> String.valueOf(e.getValue()))))
            .orElseGet(() -> ImmutableSortedMap.of());
  }

  private void printRankOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    if (shouldOutputAttributes()) {
      ImmutableSortedMap<String, ImmutableSortedMap<String, Object>> attributesWithRanks =
          getAttributesWithRankMetadata(params, env, queryResult);
      printAttributesAsJson(attributesWithRanks, printStream);
    } else {
      Map<UnflavoredBuildTarget, Integer> ranks =
          computeRanksByTarget(
              env.getTargetGraph(), env.getNodesFromQueryTargets(queryResult)::contains);

      printRankOutputAsPlainText(ranks, printStream);
    }
  }

  private void printRankOutputAsPlainText(
      Map<UnflavoredBuildTarget, Integer> ranks, PrintStream printStream) {
    ranks.entrySet().stream()
        // sort by rank and target nodes to break ties in order to make output deterministic
        .sorted(
            Comparator.comparing(Map.Entry<UnflavoredBuildTarget, Integer>::getValue)
                .thenComparing(Map.Entry::getKey))
        .forEach(
            entry -> {
              int rank = entry.getValue();
              String name = toPresentationForm(entry.getKey());
              printStream.println(rank + " " + name);
            });
  }

  private void printThriftOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      PrintStream printStream)
      throws IOException, QueryException {

    DirectedAcyclicGraph<TargetNode<?>> targetGraph = env.getTargetGraph();
    MergedTargetGraph mergedTargetGraph = MergedTargetGraph.merge(targetGraph);

    ImmutableSet<TargetNode<?>> nodesFromQueryTargets = env.getNodesFromQueryTargets(queryResult);
    ImmutableSet<UnflavoredBuildTarget> targetsFromQueryTargets =
        nodesFromQueryTargets.stream()
            .map(n -> n.getBuildTarget().getUnflavoredBuildTarget())
            .collect(ImmutableSet.toImmutableSet());

    ThriftOutput.Builder<MergedTargetNode> targetNodeBuilder =
        ThriftOutput.builder(mergedTargetGraph)
            .filter(n -> targetsFromQueryTargets.contains(n.getBuildTarget()))
            .nodeToNameMappingFunction(
                targetNode -> targetNode.getBuildTarget().getFullyQualifiedName());

    if (shouldOutputAttributes()) {
      Function<MergedTargetNode, ImmutableSortedMap<String, String>> nodeToAttributes =
          getNodeToAttributeFunction(params, env);
      targetNodeBuilder.nodeToAttributesFunction(nodeToAttributes);
    }

    ThriftOutput<MergedTargetNode> thriftOutput = targetNodeBuilder.build();
    thriftOutput.writeOutput(printStream);
  }

  private Map<String, Object> getAllUnconfiguredAttributesForTarget(
      CommandRunnerParams params, BuckQueryEnvironment env, QueryTarget target)
      throws QueryException {
    Cells cell = params.getCells();
    BuildTarget buildTarget = env.getNode((QueryBuildTarget) target).getBuildTarget();
    Cell owningCell = cell.getCell(buildTarget.getCell());
    BuildFileManifest buildFileManifest =
        env.getParserState()
            .getBuildFileManifest(
                owningCell,
                cell.getRootCell()
                    .getBuckConfigView(ParserConfig.class)
                    .getAbsolutePathToBuildFile(
                        cell.getRootCell(), buildTarget.getUnconfiguredBuildTarget()));

    String shortName = buildTarget.getShortName();
    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      throw new QueryException(
          "Could not find target " + target.toString() + " in the build manifest.");
    }

    return buildFileManifest.getTargets().get(shortName);
  }

  private Object resolveUnconfiguredAttribute(ListWithSelects unconfiguredSelect) {
    List<Object> listWithSelects = unconfiguredSelect.getElements();
    List<Object> unconfiguredAttribute = new ArrayList<>();
    for (Object element : listWithSelects) {
      if (element instanceof SelectorValue) {
        Map<String, Object> selectDictionary = ((SelectorValue) element).getDictionary();
        if (!selectDictionary.isEmpty()) {
          String selectNoMatchError = ((SelectorValue) element).getNoMatchError();
          unconfiguredAttribute.add(
              ImmutableMap.of(
                  "selectable",
                  true,
                  "conditions",
                  selectDictionary,
                  "no_match_error",
                  selectNoMatchError));
        }
      } else {
        unconfiguredAttribute.add(element);
      }
    }

    return ImmutableMap.of("concatable", true, "elements", unconfiguredAttribute);
  }

  private SortedMap<String, Object> resolveAllUnconfiguredAttributesForTarget(
      CommandRunnerParams params, BuckQueryEnvironment env, QueryBuildTarget target)
      throws QueryException {
    Map<String, Object> attributes = getAllUnconfiguredAttributesForTarget(params, env, target);
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());

    SortedMap<String, Object> convertedAttributes = new TreeMap<>();
    if (!patternsMatcher.isMatchesNone()) {
      for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
        String attributeName = attribute.getKey();
        String snakeCaseKey = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, attributeName);
        if (!patternsMatcher.matches(snakeCaseKey)) {
          continue;
        }

        Object jsonObject = attribute.getValue();
        if (!(jsonObject instanceof ListWithSelects)) {
          convertedAttributes.put(snakeCaseKey, jsonObject);
          continue;
        }

        convertedAttributes.put(
            snakeCaseKey, resolveUnconfiguredAttribute((ListWithSelects) jsonObject));
      }
    }

    if (patternsMatcher.matches(InternalTargetAttributeNames.DIRECT_DEPENDENCIES)) {
      convertedAttributes.put(
          InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
          env.getNode(target).getParseDeps().stream()
              .map(Object::toString)
              .collect(ImmutableList.toImmutableList()));
    }

    return convertedAttributes;
  }

  /**
   * Prints JSON format output where attributes that contained selects will be equal to a list of
   * maps (all information from selects) and strings/lists, which would have been evaluated with a
   * normal query
   */
  private void printJsonUnconfiguredOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {

    if (shouldOutputAttributes()) {
      ImmutableSortedMap.Builder<String, SortedMap<String, Object>> unconfiguredTargets =
          ImmutableSortedMap.naturalOrder();
      for (QueryTarget target : queryResult) {
        if (!(target instanceof QueryBuildTarget)) {
          continue;
        }

        unconfiguredTargets.put(
            toPresentationForm(env.getNode((QueryBuildTarget) target)),
            resolveAllUnconfiguredAttributesForTarget(params, env, ((QueryBuildTarget) target)));
      }
      printAttributesAsJson(unconfiguredTargets.build(), printStream);
    } else {
      CommandHelper.printJsonOutput(queryResult, printStream);
    }
  }

  /**
   * Returns {@code attributes} with included min/max rank metadata into keyed by the result of
   * {@link #toPresentationForm(TargetNode)}
   */
  private ImmutableSortedMap<String, ImmutableSortedMap<String, Object>>
      getAttributesWithRankMetadata(
          CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryBuildTarget> queryResult)
          throws QueryException {
    ImmutableSet<TargetNode<?>> nodes = env.getNodesFromQueryTargets(queryResult);
    Map<UnflavoredBuildTarget, Integer> rankEntries =
        computeRanksByTarget(env.getTargetGraph(), nodes::contains);

    ImmutableCollection<MergedTargetNode> mergedNodes = MergedTargetNode.group(nodes).values();

    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    // since some nodes differ in their flavors but ultimately have the same attributes, immutable
    // resulting map is created only after duplicates are merged by using regular HashMap
    Map<String, Integer> rankIndex =
        rankEntries.entrySet().stream()
            .collect(
                Collectors.toMap(entry -> toPresentationForm(entry.getKey()), Map.Entry::getValue));
    return ImmutableSortedMap.copyOf(
        mergedNodes.stream()
            .collect(
                Collectors.toMap(
                    AbstractQueryCommand::toPresentationForm,
                    node -> {
                      String label = toPresentationForm(node);
                      // NOTE: for resiliency in case attributes cannot be resolved a map with only
                      // minrank is returned, which means clients should be prepared to deal with
                      // potentially missing fields. Consider not returning a node in such case,
                      // since most likely an attempt to use that node would fail anyways.
                      SortedMap<String, Object> attributes =
                          getAttributes(
                                  params,
                                  env,
                                  patternsMatcher,
                                  node,
                                  DependencyStack.top(node.getBuildTarget()),
                                  whichQueryCommand())
                              .orElseGet(TreeMap::new);
                      return ImmutableSortedMap.<String, Object>naturalOrder()
                          .putAll(attributes)
                          .put(sortOutputFormat.name().toLowerCase(), rankIndex.get(label))
                          .build();
                    })),
        Comparator.<String>comparingInt(rankIndex::get).thenComparing(Comparator.naturalOrder()));
  }

  private Map<UnflavoredBuildTarget, Integer> computeRanksByTarget(
      DirectedAcyclicGraph<TargetNode<?>> graph, Predicate<TargetNode<?>> shouldContainNode) {
    HashMap<UnflavoredBuildTarget, Integer> ranks = new HashMap<>();
    for (TargetNode<?> root : ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges())) {
      ranks.put(root.getBuildTarget().getUnflavoredBuildTarget(), 0);
      new AbstractBreadthFirstTraversal<TargetNode<?>>(root) {

        @Override
        public Iterable<TargetNode<?>> visit(TargetNode<?> node) {
          if (!shouldContainNode.test(node)) {
            return ImmutableSet.of();
          }

          int nodeRank =
              Objects.requireNonNull(ranks.get(node.getBuildTarget().getUnflavoredBuildTarget()));
          ImmutableSortedSet<TargetNode<?>> sinks =
              ImmutableSortedSet.copyOf(
                  Sets.filter(graph.getOutgoingNodesFor(node), shouldContainNode::test));
          for (TargetNode<?> sink : sinks) {
            ranks.merge(
                sink.getBuildTarget().getUnflavoredBuildTarget(),
                nodeRank + 1,
                // min rank is the length of the shortest path from a root node
                // max rank is the length of the longest path from a root node
                sortOutputFormat == SortOutputFormat.MINRANK ? Math::min : Math::max);
          }
          return sinks;
        }
      }.start();
    }
    return ranks;
  }

  private static void collectAndPrintAttributesAsJson(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      ImmutableSet<String> attributes,
      PrintStream printStream,
      WhichQueryCommand whichQueryCommand)
      throws QueryException, IOException {
    printAttributesAsJson(
        collectAttributes(params, env, queryResult, attributes, whichQueryCommand), printStream);
  }

  private static <T extends SortedMap<String, Object>> void printAttributesAsJson(
      ImmutableSortedMap<String, T> result, PrintStream printStream) throws IOException {
    ObjectMappers.WRITER
        .with(
            new DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE))
        // Jackson closes stream by default - we do not want it
        .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        .writeValue(printStream, result);

    // Jackson does not append a newline after final closing bracket. Do it to make JSON look
    // nice on console.
    printStream.println();
  }

  private static ImmutableSortedMap<String, SortedMap<String, Object>> collectAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      ImmutableSet<String> attrs,
      WhichQueryCommand whichQueryCommand)
      throws QueryException {
    ImmutableList<TargetNode<?>> nodes = queryResultToTargetNodes(env, queryResult);

    ImmutableCollection<MergedTargetNode> mergedNodes = MergedTargetNode.group(nodes).values();

    PatternsMatcher patternsMatcher = new PatternsMatcher(attrs);
    // use HashMap instead of ImmutableSortedMap.Builder to allow duplicates
    // TODO(buckteam): figure out if duplicates should actually be allowed. It seems like the only
    // reason why duplicates may occur is because TargetNode's unflavored name is used as a key,
    // which may or may not be a good idea
    Map<String, SortedMap<String, Object>> attributesMap = new HashMap<>();
    for (MergedTargetNode node : mergedNodes) {
      try {
        getAttributes(
                params,
                env,
                patternsMatcher,
                node,
                DependencyStack.top(node.getBuildTarget()),
                whichQueryCommand)
            .ifPresent(attrMap -> attributesMap.put(toPresentationForm(node), attrMap));

      } catch (BuildFileParseException e) {
        params
            .getConsole()
            .printErrorText(
                "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      }
    }
    return ImmutableSortedMap.copyOf(attributesMap);
  }

  private static ImmutableList<TargetNode<?>> queryResultToTargetNodes(
      BuckQueryEnvironment env, Collection<QueryTarget> queryResult) throws QueryException {
    ImmutableList.Builder<TargetNode<?>> builder = ImmutableList.builder();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }

      builder.add(env.getNode((QueryBuildTarget) target));
    }
    return builder.build();
  }

  private static Optional<SortedMap<String, Object>> getAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      PatternsMatcher patternsMatcher,
      MergedTargetNode node,
      DependencyStack dependencyStack,
      WhichQueryCommand whichQueryCommand) {
    SortedMap<String, Object> targetNodeAttributes =
        params
            .getParser()
            .getTargetNodeRawAttributes(
                env.getParserState(),
                params.getCells().getRootCell(),
                node.getAnyNode(),
                dependencyStack);
    if (targetNodeAttributes == null) {
      params
          .getConsole()
          .printErrorText(
              "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      return Optional.empty();
    }

    SortedMap<String, Object> computedNodeAttributes =
        updateWithComputedAttributes(targetNodeAttributes, node);

    SortedMap<String, Object> attributes = new TreeMap<>();
    if (!patternsMatcher.isMatchesNone()) {
      for (Map.Entry<String, Object> entry : computedNodeAttributes.entrySet()) {
        String snakeCaseKey =
            CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, entry.getKey());
        if (patternsMatcher.matches(snakeCaseKey)) {
          attributes.put(snakeCaseKey, entry.getValue());
        }
      }

      if (whichQueryCommand == WhichQueryCommand.QUERY
          && patternsMatcher.matches(InternalTargetAttributeNames.TARGET_CONFIGURATIONS)) {
        attributes.put(
            InternalTargetAttributeNames.TARGET_CONFIGURATIONS,
            computedNodeAttributes.get(InternalTargetAttributeNames.TARGET_CONFIGURATIONS));
      }
    }
    return Optional.of(attributes);
  }

  private static SortedMap<String, Object> updateWithComputedAttributes(
      SortedMap<String, Object> rawAttributes, MergedTargetNode node) {
    SortedMap<String, Object> computedAttributes = new TreeMap<>(rawAttributes);

    List<String> computedVisibility =
        node.getAnyNode().getVisibilityPatterns().stream()
            .map(visibilityPattern -> visibilityPattern.getRepresentation())
            .collect(ImmutableList.toImmutableList());
    if (!computedVisibility.isEmpty()) {
      computedAttributes.put(VisibilityAttributes.VISIBILITY, computedVisibility);
    }

    List<String> computedWithinView =
        node.getAnyNode().getWithinViewPatterns().stream()
            .map(visibilityPattern -> visibilityPattern.getRepresentation())
            .collect(ImmutableList.toImmutableList());
    if (!computedWithinView.isEmpty()) {
      computedAttributes.put(VisibilityAttributes.WITHIN_VIEW, computedWithinView);
    }

    ImmutableList<String> targetConfigurations =
        node.getTargetConfigurations().stream()
            .map(Object::toString)
            .sorted()
            .collect(ImmutableList.toImmutableList());
    computedAttributes.put(
        InternalTargetAttributeNames.TARGET_CONFIGURATIONS, targetConfigurations);

    return computedAttributes;
  }

  private static String toPresentationForm(MergedTargetNode node) {
    return toPresentationForm(node.getBuildTarget());
  }

  private static String toPresentationForm(TargetNode<?> node) {
    return toPresentationForm(node.getBuildTarget().getUnflavoredBuildTarget());
  }

  private static String toPresentationForm(UnflavoredBuildTarget unflavoredBuildTarget) {
    return unflavoredBuildTarget.getFullyQualifiedName();
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  public static String getEscapedArgumentsListAsString(List<String> arguments) {
    return arguments.stream().map(arg -> "'" + arg + "'").collect(Collectors.joining(" "));
  }

  static String getAuditDependenciesQueryFormat(boolean isTransitive, boolean includeTests) {
    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder.append(isTransitive ? "deps('%s') " : "deps('%s', 1) ");
    if (includeTests) {
      queryBuilder.append(isTransitive ? "union deps(testsof(deps('%s')))" : "union testsof('%s')");
    }
    queryBuilder.append(" except set('%s')");
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit dependencies'. */
  static String buildAuditDependenciesQueryExpression(
      List<String> arguments, boolean isTransitive, boolean includeTests, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query ");
    queryBuilder
        .append("\"")
        .append(getAuditDependenciesQueryFormat(isTransitive, includeTests))
        .append("\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(getJsonOutputParamDeclaration());
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit tests'. */
  static String buildAuditTestsQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"testsof('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(getJsonOutputParamDeclaration());
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit owner'. */
  static String buildAuditOwnerQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"owner('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(getJsonOutputParamDeclaration());
    }
    return queryBuilder.toString();
  }

  private static String getJsonOutputParamDeclaration() {
    return " --output-format json";
  }

  /**
   * Returns PrintStream wrapper with modified {@code close()} operation.
   *
   * <p>If {@code --output-file} parameter is specified then print stream will be opened from {@code
   * outputFile}. During the {@code close()} operation this stream will be closed.
   *
   * <p>Else if {@code --output-file} parameter is not specified then standard console output will
   * be returned as print stream. {@code close()} operation is ignored in this case.
   */
  private CloseableWrapper<PrintStream> getPrintStreamWrapper(CommandRunnerParams params)
      throws IOException {
    if (outputFile == null) {
      // use stdout for output, do not close stdout stream as it is not owned here
      return CloseableWrapper.of(params.getConsole().getStdOut(), stream -> {});
    }
    return CloseableWrapper.of(
        new PrintStream(new BufferedOutputStream(Files.newOutputStream(outputFile.toPath()))),
        stream -> stream.close());
  }
}
