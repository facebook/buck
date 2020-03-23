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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.MergedTargetGraph;
import com.facebook.buck.core.model.targetgraph.MergedTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.parser.InternalTargetAttributeNames;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.google.common.base.CaseFormat;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
import org.kohsuke.args4j.Option;

/**
 * Buck subcommand which relies on the configured target graph, whose nodes' selects are evaluated
 *
 * <p>NOTE: While `query` technically runs on the configured target graph, it's existence predates
 * the configured target graph and therefore it lacks tools to operate on said graph effectively. In
 * the long run users who want to query the configured target graph should use `buck cquery`, though
 * at time of writing that command isn't production ready.
 */
public class QueryCommand extends AbstractQueryCommand {

  public QueryCommand() {
    this(OutputFormat.LIST);
  }

  public QueryCommand(OutputFormat outputFormat) {
    super();
    this.outputFormat = outputFormat;
  }

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

  @Override
  protected ImmutableSet<String> outputAttributes() {
    // There's no easy way apparently to ensure that an option has not been set
    ImmutableSet<String> deprecated = outputAttributesDeprecated.get();
    ImmutableSet<String> sane = super.outputAttributes();
    return sane.size() > deprecated.size() ? sane : deprecated;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the configured target nodes graph (see - cquery)";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    // Take the deprecated way of specifying output format and map it to the new way.
    if (generateJsonOutput) {
      outputFormat = OutputFormat.JSON;
    } else if (generateDotOutput) {
      outputFormat = generateBFSOutput ? OutputFormat.DOT_BFS : OutputFormat.DOT;
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Query", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildStateFactory(
                    params.getTypeCoercerFactory(),
                    new DefaultConstructorArgMarshaller(),
                    params.getKnownRuleTypesProvider(),
                    new ParserPythonInterpreterProvider(
                        params.getCells().getRootCell().getBuckConfig(),
                        params.getExecutableFinder()),
                    params.getWatchman(),
                    params.getBuckEventBus(),
                    params.getUnconfiguredBuildTargetFactory(),
                    params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
                .create(
                    createParsingContext(params.getCells(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                    params.getParser().getPermState())) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(
              params,
              parserState,
              createParsingContext(params.getCells(), pool.getListeningExecutorService()));
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
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
        printJsonOutput(params, env, queryResult, printStream);
        break;

      case THRIFT:
        printThriftOutput(params, env, asQueryBuildTargets(queryResult), printStream);
        break;

      case LIST:
      default:
        printListOutput(params, env, queryResult, printStream);
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Multimap<String, QueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    ImmutableSet<String> attributesFilter = outputAttributes();
    if (attributesFilter.size() > 0) {
      collectAndPrintAttributesAsJson(
          params,
          env,
          queryResultMap.asMap().values().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet()),
          attributesFilter,
          printStream);
    } else if (outputFormat == OutputFormat.JSON) {
      printJson(queryResultMap, printStream);
    } else {
      printList(queryResultMap, printStream);
    }
  }

  /** @return set as {@link QueryBuildTarget}s or throw {@link IllegalArgumentException} */
  @SuppressWarnings("unchecked")
  public Set<QueryBuildTarget> asQueryBuildTargets(Set<? extends QueryTarget> set) {
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
      printJson(queryResult, printStream);
    }
  }

  private void printListOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    if (shouldOutputAttributes()) {
      printJsonOutput(params, env, queryResult, printStream);
    } else {
      printList(queryResult, printStream);
    }
  }

  /**
   * Prints target and dependencies map into printStream.
   *
   * @param targetsAndDependencies input to query result multi map
   * @param printStream print stream for output
   */
  private void printList(
      Multimap<String, QueryTarget> targetsAndDependencies, PrintStream printStream) {
    ImmutableSortedSet.copyOf(QueryTarget::compare, targetsAndDependencies.values()).stream()
        .map(this::toPresentationForm)
        .forEach(printStream::println);
  }

  /**
   * Prints target set into printStream.
   *
   * @param targets set of query result
   * @param printStream print stream for output
   */
  private void printList(Set<QueryTarget> targets, PrintStream printStream) {
    targets.stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  /**
   * Prints target and result map's json representation into printStream.
   *
   * @param targetsAndResults input to query result multi map
   * @param printStream print stream for output
   * @throws IOException in case of IO exception during json writing operation
   */
  private void printJson(Multimap<String, QueryTarget> targetsAndResults, PrintStream printStream)
      throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            targetsAndResults, input -> toPresentationForm(Objects.requireNonNull(input)));
    ObjectMappers.WRITER.writeValue(printStream, targetsAndResultsNames.asMap());
  }

  /**
   * Prints targets set json representation into printStream.
   *
   * @param targets set of query result
   * @param printStream print stream for output
   * @throws IOException in case of IO exception during json writing operation
   */
  private void printJson(Set<QueryTarget> targets, PrintStream printStream) throws IOException {
    Set<String> targetsNames =
        targets.stream()
            .peek(Objects::requireNonNull)
            .map(this::toPresentationForm)
            .collect(ImmutableSet.toImmutableSet());

    ObjectMappers.WRITER.writeValue(printStream, targetsNames);
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

  private Function<MergedTargetNode, ImmutableSortedMap<String, String>> getNodeToAttributeFunction(
      CommandRunnerParams params, BuckQueryEnvironment env) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    return node ->
        getAttributes(
                params, env, patternsMatcher, node, DependencyStack.top(node.getBuildTarget()))
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

  /**
   * Returns {@code attributes} with included min/max rank metadata into keyed by the result of
   * {@link #toPresentationForm(MergedTargetNode)}
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
                    this::toPresentationForm,
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
                                  DependencyStack.top(node.getBuildTarget()))
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

  private void collectAndPrintAttributesAsJson(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      ImmutableSet<String> attributes,
      PrintStream printStream)
      throws QueryException, IOException {
    printAttributesAsJson(collectAttributes(params, env, queryResult, attributes), printStream);
  }

  private <T extends SortedMap<String, Object>> void printAttributesAsJson(
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

  private ImmutableSortedMap<String, SortedMap<String, Object>> collectAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      ImmutableSet<String> attrs)
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
                params, env, patternsMatcher, node, DependencyStack.top(node.getBuildTarget()))
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

  private ImmutableList<TargetNode<?>> queryResultToTargetNodes(
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

  private Optional<SortedMap<String, Object>> getAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      PatternsMatcher patternsMatcher,
      MergedTargetNode node,
      DependencyStack dependencyStack) {
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

      if (patternsMatcher.matches(InternalTargetAttributeNames.TARGET_CONFIGURATIONS)) {
        attributes.put(
            InternalTargetAttributeNames.TARGET_CONFIGURATIONS,
            computedNodeAttributes.get(InternalTargetAttributeNames.TARGET_CONFIGURATIONS));
      }
    }
    return Optional.of(attributes);
  }

  private SortedMap<String, Object> updateWithComputedAttributes(
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

  private String toPresentationForm(MergedTargetNode node) {
    return toPresentationForm(node.getBuildTarget());
  }

  private String toPresentationForm(UnflavoredBuildTarget unflavoredBuildTarget) {
    return unflavoredBuildTarget.getFullyQualifiedName();
  }

  private String toPresentationForm(QueryTarget target) {
    if (target instanceof QueryFileTarget) {
      QueryFileTarget fileTarget = (QueryFileTarget) target;
      SourcePath path = fileTarget.getPath();
      if (path instanceof PathSourcePath) {
        PathSourcePath psp = (PathSourcePath) path;
        return psp.getRelativePath().toString();
      }
    }

    return target.toString();
  }
}
