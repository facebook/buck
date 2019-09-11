/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.QueryTarget;
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
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
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
import org.codehaus.plexus.util.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FileOptionHandler;

/** Provides base functionality for query commands. */
public abstract class AbstractQueryCommand extends AbstractCommand {
  private static final Logger LOG = Logger.get(AbstractCommand.class);

  /** String used to separate distinct sets when using `%Ss` in a query */
  public static final String SET_SEPARATOR = "--";

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

    /** Format output as dot graph in bfs order */
    DOT_BFS,

    /** Format output as JSON */
    JSON,

    /** Format output as JSON with targets having unconfigured attributes * */
    JSON_UNCONFIGURED,

    /** Format output as Thrift binary */
    THRIFT,
  }

  @Option(
      name = "--output-format",
      usage =
          "Output format (default: list).\n"
              + " dot -  dot graph format.\n"
              + " dot_bfs -  dot graph format in bfs order.\n"
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
    if (queryFormat.contains("%Ss")) {
      runSingleQueryWithSet(params, env, queryFormat, formatArgs);
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
            printStreamWrapper.get());
      }
      return;
    }
    if (formatArgs.size() > 0) {
      throw new CommandLineException(
          "Must not specify format arguments without a %s or %Ss in the query");
    }
    runSingleQuery(params, env, queryFormat);
  }

  /** Format and evaluate the query using list substitution */
  private void runSingleQueryWithSet(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      String queryFormat,
      List<String> formatArgs)
      throws InterruptedException, QueryException, IOException {
    // No separators means 1 set, a single separator means 2 sets, etc
    int numberOfSetsProvided = Iterables.frequency(formatArgs, SET_SEPARATOR) + 1;
    int numberOfSetsRequested = StringUtils.countMatches(queryFormat, "%Ss");
    if (numberOfSetsProvided != numberOfSetsRequested && numberOfSetsProvided > 1) {
      String message =
          String.format(
              "Incorrect number of sets. Query uses `%%Ss` %d times but %d sets were given",
              numberOfSetsRequested, numberOfSetsProvided);
      throw new CommandLineException(message);
    }

    // If they only provided one list as args, use that for every instance of `%Ss`
    if (numberOfSetsProvided == 1) {
      String formattedQuery = queryFormat.replace("%Ss", getSetRepresentation(formatArgs));
      runSingleQuery(params, env, formattedQuery);
      return;
    }

    List<String> unusedFormatArgs = formatArgs;
    String formattedQuery = queryFormat;
    while (formattedQuery.contains("%Ss")) {
      int nextSeparatorIndex = unusedFormatArgs.indexOf(SET_SEPARATOR);
      List<String> currentSet =
          nextSeparatorIndex == -1
              ? unusedFormatArgs
              : unusedFormatArgs.subList(0, nextSeparatorIndex);
      // +1 so we don't include the separator in the next list
      unusedFormatArgs = unusedFormatArgs.subList(nextSeparatorIndex + 1, unusedFormatArgs.size());
      formattedQuery = formattedQuery.replaceFirst("%Ss", getSetRepresentation(currentSet));
    }
    runSingleQuery(params, env, formattedQuery);
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
      PrintStream printStream)
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

    LOG.debug("Printing out the following targets: %s", queryResultMap);

    if (attributesFilter.size() > 0) {
      collectAndPrintAttributesAsJson(
          params,
          env,
          queryResultMap.asMap().values().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet()),
          attributesFilter,
          printStream);
    } else if (generateJsonOutput) {
      CommandHelper.printJsonOutput(queryResultMap, printStream);
    } else {
      CommandHelper.print(queryResultMap, printStream);
    }
  }

  private void runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env, String query)
      throws IOException, InterruptedException, QueryException {
    Set<QueryTarget> queryResult = env.evaluateQuery(query);
    LOG.debug("Printing out the following targets: %s", queryResult);

    try (CloseableWrapper<PrintStream> printStreamWrapper = getPrintStreamWrapper(params)) {
      PrintStream printStream = printStreamWrapper.get();

      if (sortOutputFormat.needToSortByRank()) {
        printRankOutput(params, env, asQueryBuildTargets(queryResult), printStream);
        return;
      }

      switch (outputFormat) {
        case DOT:
          printDotOutput(
              params, env, asQueryBuildTargets(queryResult), Dot.OutputOrder.SORTED, printStream);
          break;

        case DOT_BFS:
          printDotOutput(
              params, env, asQueryBuildTargets(queryResult), Dot.OutputOrder.BFS, printStream);
          break;

        case JSON:
          printJsonOutput(params, env, queryResult, printStream);
          break;

        case JSON_UNCONFIGURED:
          printJsonUnconfiguredOutput(params, env, queryResult, printStream);
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

  private void printJsonOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws IOException, QueryException {
    if (shouldOutputAttributes()) {
      collectAndPrintAttributesAsJson(params, env, queryResult, outputAttributes(), printStream);
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
      collectAndPrintAttributesAsJson(params, env, queryResult, outputAttributes(), printStream);
    } else {
      CommandHelper.print(queryResult, printStream);
    }
  }

  private void printDotOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      Dot.OutputOrder outputOrder,
      PrintStream printStream)
      throws IOException, QueryException {
    Dot.Builder<TargetNode<?>> dotBuilder =
        Dot.builder(env.getTargetGraph(), "result_graph")
            .setNodesToFilter(env.getNodesFromQueryTargets(queryResult)::contains)
            .setNodeToName(targetNode -> targetNode.getBuildTarget().getFullyQualifiedName())
            .setNodeToTypeName(targetNode -> targetNode.getRuleType().getName())
            .setOutputOrder(outputOrder);
    if (shouldOutputAttributes()) {
      Function<TargetNode<?>, ImmutableSortedMap<String, String>> nodeToAttributes =
          getNodeToAttributeFunction(params, env);
      dotBuilder.setNodeToAttributes(nodeToAttributes);
    }
    dotBuilder.build().writeOutput(printStream);
  }

  @Nonnull
  private Function<TargetNode<?>, ImmutableSortedMap<String, String>> getNodeToAttributeFunction(
      CommandRunnerParams params, BuckQueryEnvironment env) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    return node ->
        getAttributes(params, env, patternsMatcher, node)
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
    Map<TargetNode<?>, Integer> ranks =
        computeRanks(env.getTargetGraph(), env.getNodesFromQueryTargets(queryResult)::contains);

    if (shouldOutputAttributes()) {
      ImmutableSortedMap<String, ImmutableSortedMap<String, Object>> attributesWithRanks =
          extendAttributesWithRankMetadata(params, env, ranks.entrySet());
      printAttributesAsJson(attributesWithRanks, printStream);
    } else {
      printRankOutputAsPlainText(ranks, printStream);
    }
  }

  private void printRankOutputAsPlainText(
      Map<TargetNode<?>, Integer> ranks, PrintStream printStream) {
    ranks.entrySet().stream()
        // sort by rank and target nodes to break ties in order to make output deterministic
        .sorted(
            Comparator.comparing(Map.Entry<TargetNode<?>, Integer>::getValue)
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

    ThriftOutput.Builder<TargetNode<?>> targetNodeBuilder =
        ThriftOutput.builder(env.getTargetGraph())
            .filter(env.getNodesFromQueryTargets(queryResult)::contains)
            .nodeToNameMappingFunction(
                targetNode -> targetNode.getBuildTarget().getFullyQualifiedName());

    if (shouldOutputAttributes()) {
      Function<TargetNode<?>, ImmutableSortedMap<String, String>> nodeToAttributes =
          getNodeToAttributeFunction(params, env);
      targetNodeBuilder.nodeToAttributesFunction(nodeToAttributes);
    }

    ThriftOutput<TargetNode<?>> thriftOutput = targetNodeBuilder.build();
    thriftOutput.writeOutput(printStream);
  }

  private Map<String, Object> getAllUnconfiguredAttributesForTarget(
      CommandRunnerParams params, BuckQueryEnvironment env, QueryTarget target)
      throws QueryException {
    Cell cell = params.getCell();
    BuildTarget buildTarget = env.getNode((QueryBuildTarget) target).getBuildTarget();
    Cell owningCell = cell.getCell(buildTarget);
    BuildFileManifest buildFileManifest =
        env.getParserState()
            .getBuildFileManifest(
                owningCell,
                cell.getBuckConfigView(ParserConfig.class)
                    .getAbsolutePathToBuildFile(
                        cell, buildTarget.getUnconfiguredBuildTargetView()));

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
    if (patternsMatcher.hasPatterns()) {
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
   *
   * @param rankEntries A set of pairs that map {@link TargetNode}s to their rank value (min or max)
   *     depending on {@code sortOutputFormat}.
   */
  private ImmutableSortedMap<String, ImmutableSortedMap<String, Object>>
      extendAttributesWithRankMetadata(
          CommandRunnerParams params,
          BuckQueryEnvironment env,
          Set<Map.Entry<TargetNode<?>, Integer>> rankEntries) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    // since some nodes differ in their flavors but ultimately have the same attributes, immutable
    // resulting map is created only after duplicates are merged by using regular HashMap
    Map<String, Integer> rankIndex =
        rankEntries.stream()
            .collect(
                Collectors.toMap(entry -> toPresentationForm(entry.getKey()), Map.Entry::getValue));
    return ImmutableSortedMap.copyOf(
        rankEntries.stream()
            .collect(
                Collectors.toMap(
                    entry -> toPresentationForm(entry.getKey()),
                    entry -> {
                      String label = toPresentationForm(entry.getKey());
                      // NOTE: for resiliency in case attributes cannot be resolved a map with only
                      // minrank is returned, which means clients should be prepared to deal with
                      // potentially missing fields. Consider not returning a node in such case,
                      // since most likely an attempt to use that node would fail anyways.
                      SortedMap<String, Object> attributes =
                          getAttributes(params, env, patternsMatcher, entry.getKey())
                              .orElseGet(TreeMap::new);
                      return ImmutableSortedMap.<String, Object>naturalOrder()
                          .putAll(attributes)
                          .put(sortOutputFormat.name().toLowerCase(), rankIndex.get(label))
                          .build();
                    })),
        Comparator.<String>comparingInt(rankIndex::get).thenComparing(Comparator.naturalOrder()));
  }

  private Map<TargetNode<?>, Integer> computeRanks(
      DirectedAcyclicGraph<TargetNode<?>> graph, Predicate<TargetNode<?>> shouldContainNode) {
    Map<TargetNode<?>, Integer> ranks = new HashMap<>();
    for (TargetNode<?> root : ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges())) {
      ranks.put(root, 0);
      new AbstractBreadthFirstTraversal<TargetNode<?>>(root) {

        @Override
        public Iterable<TargetNode<?>> visit(TargetNode<?> node) {
          if (!shouldContainNode.test(node)) {
            return ImmutableSet.of();
          }

          int nodeRank = Objects.requireNonNull(ranks.get(node));
          ImmutableSortedSet<TargetNode<?>> sinks =
              ImmutableSortedSet.copyOf(
                  Sets.filter(graph.getOutgoingNodesFor(node), shouldContainNode::test));
          for (TargetNode<?> sink : sinks) {
            if (!ranks.containsKey(sink)) {
              ranks.put(sink, nodeRank + 1);
            } else {
              // min rank is the length of the shortest path from a root node
              // max rank is the length of the longest path from a root node
              ranks.put(
                  sink,
                  sortOutputFormat == QueryCommand.SortOutputFormat.MINRANK
                      ? Math.min(ranks.get(sink), nodeRank + 1)
                      : Math.max(ranks.get(sink), nodeRank + 1));
            }
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
      PrintStream printStream)
      throws QueryException, IOException {
    printAttributesAsJson(collectAttributes(params, env, queryResult, attributes), printStream);
  }

  private static <T extends SortedMap<String, Object>> void printAttributesAsJson(
      ImmutableSortedMap<String, T> result, PrintStream printStream) throws IOException {
    ObjectMappers.WRITER
        .withDefaultPrettyPrinter()
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
      ImmutableSet<String> attrs)
      throws QueryException {
    PatternsMatcher patternsMatcher = new PatternsMatcher(attrs);
    // use HashMap instead of ImmutableSortedMap.Builder to allow duplicates
    // TODO(buckteam): figure out if duplicates should actually be allowed. It seems like the only
    // reason why duplicates may occur is because TargetNode's unflavored name is used as a key,
    // which may or may not be a good idea
    Map<String, SortedMap<String, Object>> attributesMap = new HashMap<>();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }
      TargetNode<?> node = env.getNode((QueryBuildTarget) target);
      try {
        getAttributes(params, env, patternsMatcher, node)
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

  private static Optional<SortedMap<String, Object>> getAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      PatternsMatcher patternsMatcher,
      TargetNode<?> node) {
    SortedMap<String, Object> targetNodeAttributes =
        params.getParser().getTargetNodeRawAttributes(env.getParserState(), params.getCell(), node);
    if (targetNodeAttributes == null) {
      params
          .getConsole()
          .printErrorText(
              "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      return Optional.empty();
    }
    SortedMap<String, Object> attributes = new TreeMap<>();
    if (patternsMatcher.hasPatterns()) {
      for (String key : targetNodeAttributes.keySet()) {
        String snakeCaseKey = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, key);
        if (patternsMatcher.matches(snakeCaseKey)) {
          attributes.put(snakeCaseKey, targetNodeAttributes.get(key));
        }
      }
    }
    return Optional.of(attributes);
  }

  private static String toPresentationForm(TargetNode<?> node) {
    return node.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName();
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  public static String getEscapedArgumentsListAsString(List<String> arguments) {
    return arguments.stream().map(arg -> "'" + arg + "'").collect(Collectors.joining(" "));
  }

  private static String getSetRepresentation(List<String> args) {
    return args.stream()
        .map(input -> "'" + input + "'")
        .collect(Collectors.joining(" ", "set(", ")"));
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
