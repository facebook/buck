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
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.param.ParamNameOrSpecial;
import com.facebook.buck.rules.param.SpecialAttr;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Buck subcommand which facilitates querying information about the unconfigured target graph. */
public class UnconfiguredQueryCommand
    extends AbstractQueryCommand<UnconfiguredQueryTarget, UnconfiguredQueryEnvironment> {

  private static final ImmutableSet<SpecialAttr> supportedComputedAttributes =
      ImmutableSet.of(SpecialAttr.BASE_PATH, SpecialAttr.BUCK_TYPE);

  private PerBuildState perBuildState;
  private TraversableGraph<UnconfiguredTargetNode> targetGraph;

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the unconfigured target graph";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("UQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState state = createPerBuildState(params, pool)) {
      perBuildState = state;
      UnconfiguredQueryEnvironment env = UnconfiguredQueryEnvironment.from(params, perBuildState);
      targetGraph = env.getTargetGraph();

      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      UnconfiguredQueryEnvironment env,
      Set<UnconfiguredQueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    OutputFormat trueOutputFormat =
        (outputFormat == OutputFormat.LIST && shouldOutputAttributes())
            ? OutputFormat.JSON
            : outputFormat;

    Optional<ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
        attributesByResult = collectAttributes(params, env, queryResult);

    switch (trueOutputFormat) {
      case LIST:
        printListOutput(queryResult, attributesByResult, printStream);
        break;
      case JSON:
        printJsonOutput(queryResult, attributesByResult, printStream);
        break;
      case DOT:
        printDotOutput(queryResult, attributesByResult, printStream);
        break;
      case DOT_COMPACT:
        printDotCompactOutput(queryResult, attributesByResult, printStream);
        break;
      case DOT_BFS:
        printDotBfsOutput(queryResult, attributesByResult, printStream);
        break;
      case DOT_BFS_COMPACT:
        printDotBfsCompactOutput(queryResult, attributesByResult, printStream);
        break;
      case THRIFT:
        printThriftOutput(queryResult, attributesByResult, printStream);
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      UnconfiguredQueryEnvironment env,
      Multimap<String, UnconfiguredQueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    if (shouldOutputAttributes()) {
      // NOTE: This is what the old `buck query` did. If you provide a multiquery and ask Buck to
      // output attributes we just combine all the query results together and print it like it was
      // all one query. Does it make sense? Maybe.
      ImmutableSet<UnconfiguredQueryTarget> combinedResults =
          ImmutableSet.copyOf(queryResultMap.values());
      printJsonOutput(
          combinedResults, collectAttributes(params, env, combinedResults), printStream);
    } else if (outputFormat == OutputFormat.LIST) {
      printListOutput(queryResultMap, printStream);
    } else if (outputFormat == OutputFormat.JSON) {
      printJsonOutput(queryResultMap, printStream);
    } else {
      throw new QueryException(
          "Multiqueries (those using `%s`) do not support printing with the given output format: "
              + outputFormat.toString());
    }
  }

  private void printListOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream) {
    Preconditions.checkState(
        !attributesByResultOptional.isPresent(), "We should be printing with JSON instead");

    queryResult.stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  private void printDotOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(
        queryResult, attributesByResultOptional, Dot.OutputOrder.UNDEFINED, false, printStream);
  }

  private void printDotCompactOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(
        queryResult, attributesByResultOptional, Dot.OutputOrder.UNDEFINED, true, printStream);
  }

  private void printDotBfsOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(queryResult, attributesByResultOptional, Dot.OutputOrder.BFS, false, printStream);
  }

  private void printDotBfsCompactOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(queryResult, attributesByResultOptional, Dot.OutputOrder.BFS, true, printStream);
  }

  private void printThriftOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    ImmutableMap<UnflavoredBuildTarget, UnconfiguredQueryTarget> resultByBuildTarget =
        queryResult.stream()
            .filter(t -> t instanceof UnconfiguredQueryBuildTarget)
            .collect(
                ImmutableMap.toImmutableMap(
                    t ->
                        ((UnconfiguredQueryBuildTarget) t)
                            .getBuildTarget()
                            .getUnflavoredBuildTarget(),
                    t -> t));

    ThriftOutput.Builder<UnconfiguredTargetNode> thriftBuilder =
        ThriftOutput.builder(targetGraph)
            .filter(n -> resultByBuildTarget.containsKey(n.getBuildTarget()))
            .nodeToNameMappingFunction(node -> node.getBuildTarget().toString());

    attributesByResultOptional.ifPresent(
        attrs ->
            thriftBuilder.nodeToAttributesFunction(
                node ->
                    attrMapToMapBySnakeCase(
                        attrs.get(resultByBuildTarget.get(node.getBuildTarget())))));
    thriftBuilder.build().writeOutput(printStream);
  }

  private void printListOutput(
      Multimap<String, UnconfiguredQueryTarget> queryResultMap, PrintStream printStream) {
    queryResultMap.values().stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  private void printJsonOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {

    Object printableObject;
    if (attributesByResultOptional.isPresent()) {
      ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>
          attributesByResult = attributesByResultOptional.get();
      printableObject =
          queryResult.stream()
              .collect(
                  ImmutableMap.toImmutableMap(this::toPresentationForm, attributesByResult::get));
    } else {
      printableObject =
          queryResult.stream()
              .peek(Objects::requireNonNull)
              .map(this::toPresentationForm)
              .collect(ImmutableSet.toImmutableSet());
    }

    prettyPrintJsonObject(printableObject, printStream);
  }

  private void printJsonOutput(
      Multimap<String, UnconfiguredQueryTarget> queryResultMap, PrintStream printStream)
      throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            queryResultMap, input -> toPresentationForm(Objects.requireNonNull(input)));
    prettyPrintJsonObject(targetsAndResultsNames.asMap(), printStream);
  }

  private void printDotGraph(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      Dot.OutputOrder outputOrder,
      boolean compactMode,
      PrintStream printStream)
      throws IOException {
    ImmutableMap<UnflavoredBuildTarget, UnconfiguredQueryTarget> resultByBuildTarget =
        queryResult.stream()
            .filter(t -> t instanceof UnconfiguredQueryBuildTarget)
            .collect(
                ImmutableMap.toImmutableMap(
                    t ->
                        ((UnconfiguredQueryBuildTarget) t)
                            .getBuildTarget()
                            .getUnflavoredBuildTarget(),
                    t -> t));

    Dot.Builder<UnconfiguredTargetNode> dotBuilder =
        Dot.builder(targetGraph, "result_graph")
            .setNodesToFilter(n -> resultByBuildTarget.containsKey(n.getBuildTarget()))
            .setNodeToName(node -> node.getBuildTarget().toString())
            .setNodeToTypeName(node -> node.getRuleType().getName())
            .setOutputOrder(outputOrder)
            .setCompactMode(compactMode);

    attributesByResultOptional.ifPresent(
        attrs ->
            dotBuilder.setNodeToAttributes(
                node ->
                    attrMapToMapBySnakeCase(
                        attrs.get(resultByBuildTarget.get(node.getBuildTarget())))));
    dotBuilder.build().writeOutput(printStream);
  }

  private Optional<
          ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
      collectAttributes(
          CommandRunnerParams params,
          UnconfiguredQueryEnvironment env,
          Set<UnconfiguredQueryTarget> queryResult) {
    if (!shouldOutputAttributes()) {
      return Optional.empty();
    }
    PatternsMatcher matcher = new PatternsMatcher(outputAttributes());
    ImmutableMap.Builder<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>
        result = ImmutableMap.builder();

    for (UnconfiguredQueryTarget target : queryResult) {
      Optional<UnconfiguredTargetNode> maybeNode = nodeForQueryTarget(env, target);
      if (!maybeNode.isPresent()) {
        continue;
      }
      UnconfiguredTargetNode node = maybeNode.get();
      getAllRawAttributes(params, node)
          .map(attrs -> getMatchingRawAndComputedAttributes(matcher, node, attrs))
          .ifPresent(
              attrs ->
                  result.put(
                      target, ImmutableSortedMap.copyOf(attrs, ParamNameOrSpecial.COMPARATOR)));
    }

    return Optional.of(result.build());
  }

  private Optional<TwoArraysImmutableHashMap<ParamName, Object>> getAllRawAttributes(
      CommandRunnerParams params, UnconfiguredTargetNode node) {
    UnflavoredBuildTarget buildTarget = node.getBuildTarget();
    Cell owningCell = params.getCells().getCell(buildTarget.getCell());
    ParserConfig cellParserConfig = owningCell.getBuckConfigView(ParserConfig.class);
    AbsPath buildFile =
        cellParserConfig.getAbsolutePathToBuildFile(
            owningCell, UnconfiguredBuildTarget.of(buildTarget), DependencyStack.top(buildTarget));
    BuildFileManifest manifest = perBuildState.getBuildFileManifest(owningCell, buildFile);

    RawTargetNode rawTargetNode = manifest.getTargets().get(buildTarget.getLocalName());
    if (rawTargetNode == null) {
      params
          .getConsole()
          .printErrorText(
              "Unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      return Optional.empty();
    }

    return Optional.of(rawTargetNode.getAttrs());
  }

  private ImmutableMap<ParamNameOrSpecial, Object> getMatchingRawAndComputedAttributes(
      PatternsMatcher matcher,
      UnconfiguredTargetNode node,
      TwoArraysImmutableHashMap<ParamName, Object> rawAttributes) {
    ImmutableMap.Builder<ParamNameOrSpecial, Object> result = ImmutableMap.builder();

    // Matching raw attributes:
    ImmutableSet<ParamName> matchingRawAttributeNames =
        getMatchingAttributeNames(matcher, rawAttributes.keySet());
    for (ParamName name : matchingRawAttributeNames) {
      Object valueWithoutSelect = eliminateSelect(rawAttributes.get(name));
      result.put(name, valueWithoutSelect);
    }

    // Matching computed attributes:
    ImmutableSet<SpecialAttr> matchingSpecialAttributes =
        getMatchingAttributeNames(matcher, supportedComputedAttributes);
    if (matchingSpecialAttributes.contains(SpecialAttr.BASE_PATH)) {
      result.put(SpecialAttr.BASE_PATH, node.getBuildTarget().getBaseName().getPath().toString());
    }
    if (matchingSpecialAttributes.contains(SpecialAttr.BUCK_TYPE)) {
      result.put(SpecialAttr.BUCK_TYPE, node.getRuleType().getName());
    }

    return result.build();
  }

  // `uquery` normally operates under the guise of "union of all possibilities". Therefore when
  // printing attributes the correct thing to do upon encountering a select is concatenate all the
  // branches together. We can't do this when building up the graph since it could lead to some type
  // coercion problems, but the JSON serializer doesn't care.
  @SuppressWarnings("unchecked")
  private Object eliminateSelect(Object value) {
    if (!(value instanceof ListWithSelects)) {
      return value;
    }
    ListWithSelects lws = (ListWithSelects) value;
    Class<?> type = lws.getType();
    if (ImmutableList.class.isAssignableFrom(type)) {
      ImmutableList.Builder<Object> listResult = ImmutableList.builder();

      for (Object element : lws.getElements()) {
        if (element instanceof SelectorValue) {
          SelectorValue selectElement = (SelectorValue) element;
          for (Object selectElementValue : selectElement.getDictionary().values()) {
            assert type.isInstance(selectElementValue);
            listResult.addAll((ImmutableList<Object>) selectElementValue);
          }
        } else {
          assert type.isInstance(element);
          listResult.addAll((ImmutableList<Object>) element);
        }
      }

      return listResult.build();
    }
    throw new RuntimeException("Unknown selector type - uquery needs improvements. Type:" + type);
  }

  private PerBuildState createPerBuildState(CommandRunnerParams params, CommandThreadManager pool) {
    ParsingContext parsingContext =
        createParsingContext(params.getCells(), pool.getListeningExecutorService())
            .withSpeculativeParsing(SpeculativeParsing.ENABLED);

    PerBuildStateFactory factory =
        new PerBuildStateFactory(
            params.getTypeCoercerFactory(),
            new DefaultConstructorArgMarshaller(),
            params.getKnownRuleTypesProvider(),
            new ParserPythonInterpreterProvider(
                params.getCells().getRootCell().getBuckConfig(), params.getExecutableFinder()),
            params.getWatchman(),
            params.getBuckEventBus(),
            params.getUnconfiguredBuildTargetFactory(),
            params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE));

    return factory.create(parsingContext, params.getParser().getPermState());
  }

  private Optional<UnconfiguredTargetNode> nodeForQueryTarget(
      UnconfiguredQueryEnvironment env, UnconfiguredQueryTarget target) {
    if (target instanceof UnconfiguredQueryBuildTarget) {
      return Optional.of(env.getNode((UnconfiguredQueryBuildTarget) target));
    } else {
      return Optional.empty();
    }
  }

  private String toPresentationForm(UnconfiguredQueryTarget queryTarget) {
    if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
      return toPresentationForm((UnconfiguredQueryBuildTarget) queryTarget);
    } else if (queryTarget instanceof QueryFileTarget) {
      return toPresentationForm((QueryFileTarget) queryTarget);
    } else {
      throw new IllegalStateException(
          String.format(
              "Unknown UnconfiguredQueryTarget implementation - %s",
              queryTarget.getClass().toString()));
    }
  }

  private String toPresentationForm(UnconfiguredQueryBuildTarget queryBuildTarget) {
    return queryBuildTarget.getBuildTarget().getUnflavoredBuildTarget().toString();
  }
}
