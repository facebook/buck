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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.graph.Dot.Builder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.codehaus.plexus.util.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class QueryCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(QueryCommand.class);

  /** String used to separate distinct sets when using `%Ss` in a query */
  public static final String SET_SEPARATOR = "--";

  /**
   * Example usage:
   *
   * <pre>
   * buck query "allpaths('//path/to:target', '//path/to:other')" --dot > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot", usage = "Print result as Dot graph")
  private boolean generateDotOutput;

  @Option(name = "--bfs", usage = "Sort the dot output in bfs order")
  private boolean generateBFSOutput;

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  /** Output format. */
  public enum OutputFormat {
    LABEL,
    /** Rank by the length of the shortest path from a root node. */
    MINRANK,
    /** Rank by the length of the longest path from a root node. */
    MAXRANK,
  }

  @Option(
    name = "--output",
    usage =
        "Output format (default: label). "
            + "minrank/maxrank: Sort the output in rank order and output the ranks "
            + "according to the length of the shortest or longest path from a root node, "
            + "respectively. This does not apply to --dot or --json."
  )
  private OutputFormat outputFormat = OutputFormat.LABEL;

  @Option(
    name = "--output-attributes",
    usage =
        "List of attributes to output, --output-attributes attr1 att2 ... attrN. "
            + "Attributes can be regular expressions. ",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  @VisibleForTesting
  Supplier<ImmutableSet<String>> outputAttributes;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  public boolean shouldGenerateBFSOutput() {
    return generateBFSOutput;
  }

  public OutputFormat getOutputFormat() {
    return outputFormat;
  }

  public boolean shouldOutputAttributes() {
    return !outputAttributes.get().isEmpty();
  }

  @Argument(handler = QueryMultiSetOptionHandler.class)
  private List<String> arguments = new ArrayList<>();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Query", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildStateFactory()
                .create(
                    params.getTypeCoercerFactory(),
                    params.getParser().getPermState(),
                    new ConstructorArgMarshaller(params.getTypeCoercerFactory()),
                    params.getBuckEventBus(),
                    new ParserPythonInterpreterProvider(
                        params.getCell().getBuckConfig(), params.getExecutableFinder()),
                    pool.getListeningExecutorService(),
                    params.getCell(),
                    params.getKnownBuildRuleTypesProvider(),
                    getEnableParserProfiling(),
                    SpeculativeParsing.ENABLED)) {
      ListeningExecutorService executor = pool.getListeningExecutorService();
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(params, parserState, executor, getEnableParserProfiling());
      return formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
  }

  @VisibleForTesting
  ExitCode formatAndRunQuery(CommandRunnerParams params, BuckQueryEnvironment env)
      throws IOException, InterruptedException, QueryException {
    String queryFormat = arguments.get(0);
    List<String> formatArgs = arguments.subList(1, arguments.size());
    if (queryFormat.contains("%Ss")) {
      return runSingleQueryWithSet(params, env, queryFormat, formatArgs);
    }
    if (queryFormat.contains("%s")) {
      return runMultipleQuery(params, env, queryFormat, formatArgs, shouldGenerateJsonOutput());
    }
    if (formatArgs.size() > 0) {
      // TODO: buck_team: return ExitCode.COMMANDLINE_ERROR
      throw new HumanReadableException(
          "Must not specify format arguments without a %s or %Ss in the query");
    }
    return runSingleQuery(params, env, queryFormat);
  }

  /** Format and evaluate the query using list substitution */
  ExitCode runSingleQueryWithSet(
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
      throw new HumanReadableException(message);
    }

    // If they only provided one list as args, use that for every instance of `%Ss`
    if (numberOfSetsProvided == 1) {
      String formattedQuery = queryFormat.replace("%Ss", getSetRepresentation(formatArgs));
      return runSingleQuery(params, env, formattedQuery);
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
    return runSingleQuery(params, env, formattedQuery);
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage: buck query <query format>
   * <input1> <input2> <...> <inputN>
   */
  static ExitCode runMultipleQuery(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      String queryFormat,
      List<String> inputsFormattedAsBuildTargets,
      boolean generateJsonOutput)
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
      QueryExpression expr = QueryExpression.parse(query, env);
      expr.collectTargetPatterns(targetLiterals);
    }
    env.preloadTargetPatterns(targetLiterals);

    // Now execute the query on the arguments one-by-one.
    TreeMultimap<String, QueryTarget> queryResultMap = TreeMultimap.create();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      ImmutableSet<QueryTarget> queryResult = env.evaluateQuery(query);
      queryResultMap.putAll(input, queryResult);
    }

    LOG.debug("Printing out the following targets: " + queryResultMap);
    if (generateJsonOutput) {
      CommandHelper.printJSON(params, queryResultMap);
    } else {
      CommandHelper.printToConsole(params, queryResultMap);
    }
    return ExitCode.SUCCESS;
  }

  ExitCode runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env, String query)
      throws IOException, InterruptedException, QueryException {
    ImmutableSet<QueryTarget> queryResult = env.evaluateQuery(query);

    LOG.debug("Printing out the following targets: " + queryResult);
    if (getOutputFormat() == OutputFormat.MINRANK || getOutputFormat() == OutputFormat.MAXRANK) {
      printRankOutput(params, env, queryResult, getOutputFormat());
    } else if (shouldGenerateDotOutput()) {
      printDotOutput(params, env, queryResult);
    } else if (shouldOutputAttributes()) {
      collectAndPrintAttributesAsJson(params, env, queryResult);
    } else if (shouldGenerateJsonOutput()) {
      CommandHelper.printJSON(params, queryResult);
    } else {
      CommandHelper.printToConsole(params, queryResult);
    }
    return ExitCode.SUCCESS;
  }

  private void printDotOutput(
      CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryTarget> queryResult)
      throws IOException, QueryException {
    Builder<TargetNode<?, ?>> dotBuilder =
        Dot.builder(env.getTargetGraph(), "result_graph")
            .setNodesToFilter(env.getNodesFromQueryTargets(queryResult)::contains)
            .setNodeToName(targetNode -> targetNode.getBuildTarget().getFullyQualifiedName())
            .setNodeToTypeName(targetNode -> targetNode.getBuildRuleType().getName())
            .setBfsSorted(shouldGenerateBFSOutput());
    if (shouldOutputAttributes()) {
      PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes.get());
      dotBuilder.setNodeToAttributes(
          node ->
              getAttributes(params, env, patternsMatcher, node)
                  .map(
                      attrs ->
                          attrs
                              .entrySet()
                              .stream()
                              .collect(
                                  ImmutableSortedMap.toImmutableSortedMap(
                                      Comparator.naturalOrder(),
                                      Entry::getKey,
                                      e -> String.valueOf(e.getValue()))))
                  .orElse(ImmutableSortedMap.of()));
    }
    dotBuilder.build().writeOutput(params.getConsole().getStdOut());
  }

  private void printRankOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      OutputFormat outputFormat)
      throws QueryException {
    Map<TargetNode<?, ?>, Integer> ranks =
        computeRanks(
            env.getTargetGraph(),
            env.getNodesFromQueryTargets(queryResult)::contains,
            outputFormat);

    PrintStream stdOut = params.getConsole().getStdOut();
    if (shouldOutputAttributes()) {
      ImmutableSortedMap<String, ImmutableSortedMap<String, Object>> attributesWithRanks =
          extendAttributesWithRankMetadata(params, env, outputFormat, ranks.entrySet());
      printAttributesAsJson(attributesWithRanks, stdOut);
    } else {
      printRankOutputAsPlainText(ranks, stdOut);
    }
  }

  private void printRankOutputAsPlainText(
      Map<TargetNode<?, ?>, Integer> ranks, PrintStream stdOut) {
    ranks
        .entrySet()
        .stream()
        // sort by rank and target nodes to break ties in order to make output deterministic
        .sorted(
            Comparator.comparing(Entry<TargetNode<?, ?>, Integer>::getValue)
                .thenComparing(Comparator.comparing(Entry<TargetNode<?, ?>, Integer>::getKey)))
        .forEach(
            entry -> {
              int rank = entry.getValue();
              String name = toPresentationForm(entry.getKey());
              stdOut.println(rank + " " + name);
            });
  }

  /**
   * Returns {@code attributes} with included min/max rank metadata into keyed by the result of
   * {@link #toPresentationForm(TargetNode)}
   *
   * @param rankEntries A set of pairs that map {@link TargetNode}s to their rank value (min or max)
   *     depending on {@code outputFormat}.
   */
  private ImmutableSortedMap<String, ImmutableSortedMap<String, Object>>
      extendAttributesWithRankMetadata(
          CommandRunnerParams params,
          BuckQueryEnvironment env,
          OutputFormat outputFormat,
          Set<Entry<TargetNode<?, ?>, Integer>> rankEntries) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes.get());
    // since some nodes differ in their flavors but ultimately have the same attributes, immutable
    // resulting map is created only after duplicates are merged by using regular HashMap
    Map<String, Integer> rankIndex =
        rankEntries
            .stream()
            .collect(
                Collectors.toMap(entry -> toPresentationForm(entry.getKey()), Entry::getValue));
    return ImmutableSortedMap.copyOf(
        rankEntries
            .stream()
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
                          .put(outputFormat.name().toLowerCase(), rankIndex.get(label))
                          .build();
                    })),
        Comparator.<String>comparingInt(rankIndex::get).thenComparing(Comparator.naturalOrder()));
  }

  private Map<TargetNode<?, ?>, Integer> computeRanks(
      DirectedAcyclicGraph<TargetNode<?, ?>> graph,
      Predicate<TargetNode<?, ?>> shouldContainNode,
      OutputFormat outputFormat) {
    Map<TargetNode<?, ?>, Integer> ranks = new HashMap<>();
    for (TargetNode<?, ?> root : ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges())) {
      ranks.put(root, 0);
      new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(root) {

        @Override
        public Iterable<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {
          if (!shouldContainNode.test(node)) {
            return ImmutableSet.of();
          }

          int nodeRank = Preconditions.checkNotNull(ranks.get(node));
          ImmutableSortedSet<TargetNode<?, ?>> sinks =
              ImmutableSortedSet.copyOf(
                  Sets.filter(graph.getOutgoingNodesFor(node), shouldContainNode::test));
          for (TargetNode<?, ?> sink : sinks) {
            if (!ranks.containsKey(sink)) {
              ranks.put(sink, nodeRank + 1);
            } else {
              // min rank is the length of the shortest path from a root node
              // max rank is the length of the longest path from a root node
              ranks.put(
                  sink,
                  outputFormat == OutputFormat.MINRANK
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

  private void collectAndPrintAttributesAsJson(
      CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryTarget> queryResult)
      throws QueryException {
    ImmutableSortedMap<String, SortedMap<String, Object>> result =
        collectAttributes(params, env, queryResult);
    printAttributesAsJson(result, params.getConsole().getStdOut());
  }

  private <T extends SortedMap<String, Object>> void printAttributesAsJson(
      ImmutableSortedMap<String, T> result, PrintStream outputStream) {
    StringWriter stringWriter = new StringWriter();
    try {
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, result);
    } catch (IOException e) {
      // Shouldn't be possible while writing to a StringWriter...
      throw new RuntimeException(e);
    }
    String output = stringWriter.getBuffer().toString();
    outputStream.println(output);
  }

  private ImmutableSortedMap<String, SortedMap<String, Object>> collectAttributes(
      CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryTarget> queryResult)
      throws QueryException {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes.get());
    // use HashMap instead of ImmutableSortedMap.Builder to allow duplicates
    // TODO(buckteam): figure out if duplicates should actually be allowed. It seems like the only
    // reason why duplicates may occur is because TargetNode's unflavored name is used as a key,
    // which may or may not be a good idea
    Map<String, SortedMap<String, Object>> attributesMap = new HashMap<>();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }
      TargetNode<?, ?> node = env.getNode(target);
      try {
        Optional<SortedMap<String, Object>> attributes =
            getAttributes(params, env, patternsMatcher, node);
        if (!attributes.isPresent()) {
          continue;
        }

        attributesMap.put(toPresentationForm(node), attributes.get());
      } catch (BuildFileParseException e) {
        params
            .getConsole()
            .printErrorText(
                "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
        continue;
      }
    }
    return ImmutableSortedMap.<String, SortedMap<String, Object>>naturalOrder()
        .putAll(attributesMap)
        .build();
  }

  private Optional<SortedMap<String, Object>> getAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      PatternsMatcher patternsMatcher,
      TargetNode<?, ?> node) {
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

  private String toPresentationForm(TargetNode<?, ?> node) {
    return node.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName();
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the target nodes graph";
  }

  public static String getEscapedArgumentsListAsString(List<String> arguments) {
    return Joiner.on(" ").join(Lists.transform(arguments, arg -> "'" + arg + "'"));
  }

  private static String getSetRepresentation(List<String> args) {
    String argsList = Joiner.on(' ').join(Iterables.transform(args, input -> "'" + input + "'"));
    return "set(" + argsList + ")";
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
    queryBuilder.append("\"" + getAuditDependenciesQueryFormat(isTransitive, includeTests) + "\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit tests'. */
  static String buildAuditTestsQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"testsof('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit owner'. */
  static String buildAuditOwnerQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"owner('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }
}
