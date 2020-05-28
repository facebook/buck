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
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
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
import com.facebook.buck.rules.param.ParamNameOrSpecial;
import com.facebook.buck.rules.param.SpecialAttr;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Supplier;
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

  private PerBuildState perBuildState;

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
      perBuildState = parserState;
      ConfiguredQueryEnvironment env =
          ConfiguredQueryEnvironment.from(
              params,
              LegacyQueryUniverse.from(params, parserState),
              perBuildState.getParsingContext());
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
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
        printListOutput(params, env, queryResult, printStream);
        break;

      default:
        throw new AssertionError("unreachable");
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
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
      ConfiguredQueryEnvironment env,
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
      ConfiguredQueryEnvironment env,
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
      ConfiguredQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      Dot.OutputOrder outputOrder,
      PrintStream printStream,
      boolean compactMode)
      throws IOException, QueryException {
    MergedTargetGraph mergedTargetGraph =
        MergedTargetGraph.merge(env.getTargetUniverse().getTargetGraph());

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
      dotBuilder.setNodeToAttributes(getNodeToAttributeFunction(params));
    }
    dotBuilder.build().writeOutput(printStream);
  }

  private Function<MergedTargetNode, ImmutableSortedMap<String, Object>> getNodeToAttributeFunction(
      CommandRunnerParams params) {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes());
    return node ->
        getAllAttributes(params, node, DependencyStack.top(node.getBuildTarget()))
            .map(
                attrs -> {
                  return attrMapToMapBySnakeCase(getMatchingAttributes(patternsMatcher, attrs));
                })
            .map(ImmutableSortedMap::copyOf)
            .orElse(ImmutableSortedMap.of());
  }

  private void printThriftOutput(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
      Set<QueryBuildTarget> queryResult,
      PrintStream printStream)
      throws IOException, QueryException {

    DirectedAcyclicGraph<TargetNode<?>> targetGraph = env.getTargetUniverse().getTargetGraph();
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
      targetNodeBuilder.nodeToAttributesFunction(getNodeToAttributeFunction(params));
    }

    ThriftOutput<MergedTargetNode> thriftOutput = targetNodeBuilder.build();
    thriftOutput.writeOutput(printStream);
  }

  private void collectAndPrintAttributesAsJson(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
      Set<QueryTarget> queryResult,
      ImmutableSet<String> attributes,
      PrintStream printStream)
      throws QueryException, IOException {
    ImmutableSortedMap<String, ImmutableSortedMap<ParamNameOrSpecial, Object>>
        resultWithAttributes = collectAttributes(params, env, queryResult, attributes);
    prettyPrintJsonObject(resultWithAttributes, printStream);
  }

  private ImmutableSortedMap<String, ImmutableSortedMap<ParamNameOrSpecial, Object>>
      collectAttributes(
          CommandRunnerParams params,
          ConfiguredQueryEnvironment env,
          Set<QueryTarget> queryResult,
          ImmutableSet<String> attributes)
          throws QueryException {
    ImmutableList<TargetNode<?>> nodes = queryResultToTargetNodes(env, queryResult);

    ImmutableCollection<MergedTargetNode> mergedNodes = MergedTargetNode.group(nodes).values();

    PatternsMatcher patternsMatcher = new PatternsMatcher(attributes);
    // use HashMap instead of ImmutableSortedMap.Builder to allow duplicates
    // TODO(buckteam): figure out if duplicates should actually be allowed. It seems like the only
    // reason why duplicates may occur is because TargetNode's unflavored name is used as a key,
    // which may or may not be a good idea
    Map<String, ImmutableSortedMap<ParamNameOrSpecial, Object>> attributesMap = new HashMap<>();
    for (MergedTargetNode node : mergedNodes) {
      try {
        getAllAttributes(params, node, DependencyStack.top(node.getBuildTarget()))
            .map(attrs -> getMatchingAttributes(patternsMatcher, attrs))
            .ifPresent(
                attrs ->
                    attributesMap.put(
                        toPresentationForm(node),
                        ImmutableSortedMap.copyOf(attrs, ParamNameOrSpecial.COMPARATOR)));

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
      ConfiguredQueryEnvironment env, Collection<QueryTarget> queryResult) throws QueryException {
    ImmutableList.Builder<TargetNode<?>> builder = ImmutableList.builder();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }

      QueryBuildTarget queryBuildTarget = (QueryBuildTarget) target;
      env.getTargetUniverse().getNode(queryBuildTarget.getBuildTarget()).ifPresent(builder::add);
    }
    return builder.build();
  }

  private Optional<ImmutableMap<ParamNameOrSpecial, Object>> getAllAttributes(
      CommandRunnerParams params, MergedTargetNode node, DependencyStack dependencyStack) {
    ImmutableMap.Builder<ParamNameOrSpecial, Object> result = ImmutableMap.builder();
    SortedMap<ParamNameOrSpecial, Object> rawAttributes =
        params
            .getParser()
            .getTargetNodeRawAttributes(
                perBuildState, params.getCells().getRootCell(), node.getAnyNode(), dependencyStack);
    if (rawAttributes == null) {
      params
          .getConsole()
          .printErrorText(
              "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      return Optional.empty();
    }

    result.putAll(rawAttributes);
    result.putAll(getMergedNodeComputedAttributes(node));
    return Optional.of(result.build());
  }

  private ImmutableMap<ParamNameOrSpecial, Object> getMergedNodeComputedAttributes(
      MergedTargetNode node) {
    ImmutableMap.Builder<ParamNameOrSpecial, Object> result = ImmutableMap.builder();

    ImmutableList<String> targetConfigurations =
        node.getTargetConfigurations().stream()
            .map(Object::toString)
            .sorted()
            .collect(ImmutableList.toImmutableList());
    result.put(SpecialAttr.TARGET_CONFIGURATIONS, targetConfigurations);

    return result.build();
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
