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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.RuleDescriptor;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversalWithPayload;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.GraphTraversableWithPayload;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.query.AllPathsFunction;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.BuildFileFunction;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.EvaluatingQueryEnvironment;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.OwnerFunction;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.TestsOfFunction;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/** QueryEnvironment implementation that operates over the unconfigured target graph. */
@SuppressWarnings("unused")
public class UnconfiguredQueryEnvironment
    implements EvaluatingQueryEnvironment<UnconfiguredQueryTarget> {

  private final Parser parser;
  private final PerBuildState perBuildState;
  private final Cell rootCell;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredQueryTargetEvaluator targetEvaluator;
  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private MutableDirectedGraph<UnconfiguredTargetNode> graph =
      MutableDirectedGraph.createConcurrent();
  private Map<UnflavoredBuildTarget, UnconfiguredTargetNode> targetsToNodes =
      new ConcurrentHashMap<>();
  private Map<UnflavoredBuildTarget, Set<UnconfiguredBuildTarget>> buildTargetToDependencies =
      new ConcurrentHashMap<>();
  // This should only be modified during query execution though, so we don't need a concurrent map.
  private final Map<UnflavoredBuildTarget, UnconfiguredQueryBuildTarget> buildTargetToQueryTarget =
      new HashMap<>();

  public UnconfiguredQueryEnvironment(
      Parser parser,
      PerBuildState perBuildState,
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredQueryTargetEvaluator targetEvaluator) {
    this.parser = parser;
    this.perBuildState = perBuildState;
    this.rootCell = rootCell;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.typeCoercerFactory = typeCoercerFactory;
    this.targetEvaluator = targetEvaluator;
  }

  /** Convenience constructor */
  public static UnconfiguredQueryEnvironment from(
      CommandRunnerParams params, PerBuildState perBuildState) {
    Cell rootCell = params.getCells().getRootCell();
    Parser parser = params.getParser();
    UnconfiguredQueryTargetEvaluator targetEvaluator =
        UnconfiguredQueryTargetEvaluator.from(
            parser,
            perBuildState,
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            params.getBuckConfig());

    return new UnconfiguredQueryEnvironment(
        parser,
        perBuildState,
        rootCell,
        params.getKnownRuleTypesProvider(),
        params.getTypeCoercerFactory(),
        targetEvaluator);
  }

  @Override
  public Set<UnconfiguredQueryTarget> evaluateQuery(QueryExpression<UnconfiguredQueryTarget> expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator<UnconfiguredQueryTarget>().eval(expr, this);
  }

  @Override
  public void preloadTargetPatterns(Iterable<String> patterns)
      throws QueryException, InterruptedException {
    for (String pattern : patterns) {
      targetEvaluator.evaluateTarget(pattern);
    }
  }

  @Override
  public Iterable<QueryFunction<UnconfiguredQueryTarget>> getFunctions() {
    return ImmutableList.of(
        new AllPathsFunction<>(),
        new AttrFilterFunction<>(),
        new AttrRegexFilterFunction<>(),
        new BuildFileFunction<>(),
        new DepsFunction<>(),
        new DepsFunction.FirstOrderDepsFunction<>(),
        new DepsFunction.LookupFunction<>(),
        new InputsFunction<>(),
        new FilterFunction<>(),
        new KindFunction<>(),
        new LabelsFunction<>(),
        new OwnerFunction<>(),
        new RdepsFunction<>(),
        new TestsOfFunction<>());
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTransitiveClosure(
      Set<UnconfiguredQueryTarget> targets) {
    Set<UnconfiguredTargetNode> nodes = new LinkedHashSet<>(targets.size());
    for (UnconfiguredQueryTarget queryTarget : targets) {
      if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
        getNode((UnconfiguredQueryBuildTarget) queryTarget).ifPresent(nodes::add);
      }
    }

    ImmutableSet.Builder<UnconfiguredQueryTarget> result = ImmutableSet.builder();

    new AbstractBreadthFirstTraversal<UnconfiguredTargetNode>(nodes) {
      @Override
      public Iterable<UnconfiguredTargetNode> visit(UnconfiguredTargetNode node) {
        result.add(getOrCreateQueryBuildTarget(node.getBuildTarget()));
        return getDepsForNode(node).stream()
            .map((buildTarget) -> targetsToNodes.get(buildTarget.getUnflavoredBuildTarget()))
            .collect(ImmutableSet.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(Set<UnconfiguredQueryTarget> targets) throws QueryException {
    ImmutableSet<UnconfiguredBuildTarget> newBuildTargets =
        targets.stream()
            .flatMap(this::filterNonBuildTargets)
            .map(UnconfiguredQueryBuildTarget::getBuildTarget)
            .filter(
                buildTarget -> !targetsToNodes.containsKey(buildTarget.getUnflavoredBuildTarget()))
            .collect(ImmutableSet.toImmutableSet());

    // TODO(mkosiba): This looks more and more like the Parser.buildTargetGraph method. Unify the
    // two.

    ConcurrentHashMap<UnconfiguredBuildTarget, ListenableFuture<Unit>> jobsCache =
        new ConcurrentHashMap<>();

    try {
      List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
      for (UnconfiguredBuildTarget buildTarget : newBuildTargets) {
        discoverNewTargetsConcurrently(buildTarget, DependencyStack.top(buildTarget), jobsCache)
            .ifPresent(dep -> depsFuture.add(dep));
      }
      Futures.allAsList(depsFuture).get();
    } catch (ExecutionException e) {
      if (e.getCause() != null) {
        throw new QueryException(
            e.getCause(),
            "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e.getCause()));
      }
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
    } catch (BuildFileParseException | InterruptedException e) {
      throw new QueryException(
          e, "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    GraphTraversableWithPayload<UnconfiguredBuildTarget, UnconfiguredTargetNode> traversable =
        target -> {
          UnconfiguredTargetNode node = assertNode(target);

          // If a node has been added to the graph it means it and all of its children have been
          // visited by an acyclic traversal and added to the graph. From this it follows that there
          // are no outgoing edges from the graph (as it had been "fully" explored before) back out
          // to the set of nodes we're currently exploring. Based on that:
          //  - we can't have a cycle involving the "old" nodes,
          //  - there are no new edges or nodes to be discovered by descending into the "old" nodes,
          // making this node safe to skip.
          if (graph.getNodes().contains(node)) {
            return new Pair<>(node, ImmutableSet.<UnconfiguredBuildTarget>of().iterator());
          }
          return new Pair<>(node, getDepsForNode(node).iterator());
        };

    AcyclicDepthFirstPostOrderTraversalWithPayload<UnconfiguredBuildTarget, UnconfiguredTargetNode>
        targetNodeTraversal = new AcyclicDepthFirstPostOrderTraversalWithPayload<>(traversable);
    try {
      for (Pair<UnconfiguredBuildTarget, UnconfiguredTargetNode> entry :
          targetNodeTraversal.traverse(newBuildTargets)) {
        UnconfiguredTargetNode node = entry.getSecond();
        graph.addNode(node);
        for (UnconfiguredBuildTarget dep : getDepsForNode(node)) {
          graph.addEdge(node, assertNode(dep));
        }
      }
    } catch (CycleException e) {
      throw new QueryException(e, e.getMessage());
    }
  }

  @Override
  public QueryEnvironment.TargetEvaluator<UnconfiguredQueryTarget> getTargetEvaluator() {
    return targetEvaluator;
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getFwdDeps(Iterable<UnconfiguredQueryTarget> targets)
      throws QueryException {
    return Streams.stream(targets)
        .flatMap(this::filterNonBuildTargets)
        .map(UnconfiguredQueryBuildTarget::getBuildTarget)
        .map(this::assertNode)
        .flatMap(n -> this.getDepsForNode(n).stream())
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<UnconfiguredQueryTarget> getReverseDeps(Iterable<UnconfiguredQueryTarget> targets)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public Set<UnconfiguredQueryTarget> getInputs(UnconfiguredQueryTarget target)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTestsForTarget(UnconfiguredQueryTarget target)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getBuildFiles(Set<UnconfiguredQueryTarget> targets) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getConfiguredTargets(
      Set<UnconfiguredQueryTarget> targets, Optional<String> configurationName)
      throws QueryException {
    throw new UnsupportedOperationException(
        "Calls to `getConfiguredTargets` are not valid for UnconfiguredQueryEnvironment");
  }

  @Override
  public String getTargetKind(UnconfiguredQueryTarget target) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTargetsInAttribute(
      UnconfiguredQueryTarget target, ParamName attribute) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      UnconfiguredQueryTarget target, ParamName attribute, Predicate<Object> predicate)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  private Optional<ListenableFuture<Unit>> discoverNewTargetsConcurrently(
      UnconfiguredBuildTarget buildTarget,
      DependencyStack dependencyStack,
      ConcurrentHashMap<UnconfiguredBuildTarget, ListenableFuture<Unit>> jobsCache)
      throws BuildFileParseException {
    ListenableFuture<Unit> job = jobsCache.get(buildTarget);
    if (job != null) {
      return Optional.empty();
    }
    SettableFuture<Unit> newJob = SettableFuture.create();
    if (jobsCache.putIfAbsent(buildTarget, newJob) != null) {
      return Optional.empty();
    }

    ListenableFuture<Unit> future =
        Futures.transformAsync(
            parser.getUnconfiguredTargetNodeJob(perBuildState, buildTarget, dependencyStack),
            targetNode -> {
              targetsToNodes.put(buildTarget.getUnflavoredBuildTarget(), targetNode);
              List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
              Set<UnconfiguredBuildTarget> parseDeps = getDepsForNode(targetNode);
              for (UnconfiguredBuildTarget parseDep : parseDeps) {
                discoverNewTargetsConcurrently(parseDep, dependencyStack.child(parseDep), jobsCache)
                    .ifPresent(
                        depWork ->
                            depsFuture.add(
                                attachParentNodeToErrorMessage(buildTarget, parseDep, depWork)));
              }
              return Futures.transform(
                  Futures.allAsList(depsFuture),
                  Functions.constant(null),
                  MoreExecutors.directExecutor());
            });
    newJob.setFuture(future);
    return Optional.of(newJob);
  }

  private static ListenableFuture<Unit> attachParentNodeToErrorMessage(
      UnconfiguredBuildTarget buildTarget,
      UnconfiguredBuildTarget parseDep,
      ListenableFuture<Unit> depWork) {
    return Futures.catchingAsync(
        depWork,
        Exception.class,
        exceptionInput -> {
          if (exceptionInput instanceof BuildFileParseException) {
            if (exceptionInput instanceof BuildTargetException) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildTargetException) exceptionInput);
            } else {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildFileParseException) exceptionInput);
            }
          }
          throw exceptionInput;
        });
  }

  /**
   * Filter function to remove any {@code UnconfiguredQueryTarget}s that don't refer to build
   * targets
   */
  private Stream<UnconfiguredQueryBuildTarget> filterNonBuildTargets(
      UnconfiguredQueryTarget queryTarget) {
    if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
      return Stream.of((UnconfiguredQueryBuildTarget) queryTarget);
    } else {
      return Stream.of();
    }
  }

  private UnconfiguredQueryBuildTarget getOrCreateQueryBuildTarget(
      UnconfiguredBuildTarget buildTarget) {
    return getOrCreateQueryBuildTarget(buildTarget.getUnflavoredBuildTarget());
  }

  private UnconfiguredQueryBuildTarget getOrCreateQueryBuildTarget(
      UnflavoredBuildTarget buildTarget) {
    return buildTargetToQueryTarget.computeIfAbsent(buildTarget, UnconfiguredQueryBuildTarget::of);
  }

  @Nonnull
  private UnconfiguredTargetNode assertNode(UnconfiguredBuildTarget buildTarget) {
    UnflavoredBuildTarget unflavored = buildTarget.getUnflavoredBuildTarget();
    return Objects.requireNonNull(
        targetsToNodes.get(unflavored),
        () -> "Couldn't find UnconfiguredTargetNode for " + unflavored);
  }

  private Optional<UnconfiguredTargetNode> getNode(UnconfiguredQueryBuildTarget queryBuildTarget) {
    return getNode(queryBuildTarget.getBuildTarget().getUnflavoredBuildTarget());
  }

  private Optional<UnconfiguredTargetNode> getNode(UnconfiguredBuildTarget buildTarget) {
    return getNode(buildTarget.getUnflavoredBuildTarget());
  }

  private Optional<UnconfiguredTargetNode> getNode(UnflavoredBuildTarget buildTarget) {
    return Optional.ofNullable(targetsToNodes.get(buildTarget));
  }

  private Set<UnconfiguredBuildTarget> getDepsForNode(UnconfiguredTargetNode node) {
    return buildTargetToDependencies.computeIfAbsent(
        node.getBuildTarget(), (buildTarget) -> computeDepsForNode(node));
  }

  @SuppressWarnings("unchecked")
  private ImmutableSet<UnconfiguredBuildTarget> computeDepsForNode(UnconfiguredTargetNode node) {
    ImmutableSet.Builder<UnconfiguredBuildTarget> result = ImmutableSet.builder();
    TwoArraysImmutableHashMap<ParamName, Object> attributes = node.getAttributes();

    ParamsInfo paramsInfo = lookupParamsInfoForRule(node.getBuildTarget(), node.getRuleType());
    for (ParamName name : attributes.keySet()) {
      ParamInfo<?> info = paramsInfo.getByName(name);
      TypeCoercer<Object, ?> coercer = (TypeCoercer<Object, ?>) info.getTypeCoercer();

      Object value = attributes.get(name);
      // `selects` play a bit of a funny role, because our `ParamsInfo` _says_ that an attribute
      // should be of type X, but instead it's a `SelectorList<X>`. Handle this case explicitly.
      if (value instanceof SelectorList) {
        SelectorList<Object> valueAsSelectorList = (SelectorList<Object>) value;
        valueAsSelectorList.traverseSelectors(
            (selectorKey, selectorValue) -> {
              selectorKey
                  .getBuildTarget()
                  .ifPresent(target -> result.add(target.getUnconfiguredBuildTarget()));
              collectBuildTargetsFromCoercerTraversal(result, coercer, selectorValue);
            });
      } else {
        collectBuildTargetsFromCoercerTraversal(result, coercer, value);
      }
    }
    return result.build();
  }

  private ParamsInfo lookupParamsInfoForRule(UnflavoredBuildTarget buildTarget, RuleType ruleType) {
    Cell cell = rootCell.getCell(buildTarget.getCell());
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleDescriptor<?> descriptor = knownRuleTypes.getDescriptorByName(ruleType.getName());
    return typeCoercerFactory
        .getNativeConstructorArgDescriptor(descriptor.getConstructorArgType())
        .getParamsInfo();
  }

  private void collectBuildTargetsFromCoercerTraversal(
      ImmutableSet.Builder<UnconfiguredBuildTarget> result,
      TypeCoercer<Object, ?> coercer,
      Object value) {
    coercer.traverseUnconfigured(
        rootCell.getCellNameResolver(),
        value,
        object -> {
          if (object instanceof UnconfiguredBuildTarget) {
            result.add((UnconfiguredBuildTarget) object);
          } else if (object instanceof UnflavoredBuildTarget) {
            result.add(UnconfiguredBuildTarget.of((UnflavoredBuildTarget) object));
          } else if (object instanceof UnconfiguredSourcePath) {
            ((UnconfiguredSourcePath) object)
                .match(
                    new UnconfiguredSourcePath.Matcher<Unit>() {
                      @Override
                      public Unit path(CellRelativePath path) {
                        return Unit.UNIT;
                      }

                      @Override
                      public Unit buildTarget(
                          UnconfiguredBuildTargetWithOutputs targetWithOutputs) {
                        result.add(targetWithOutputs.getBuildTarget());
                        return Unit.UNIT;
                      }
                    });
          }
        });
  }
}
