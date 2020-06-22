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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.ConfiguredQueryBuildTarget;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A query environment that can be used for graph-enhancement, including macro expansion or dynamic
 * dependency resolution.
 *
 * <p>The query environment supports the following functions
 *
 * <pre>
 *  attrfilter
 *  deps
 *  inputs
 *  except
 *  inputs
 *  intersect
 *  filter
 *  kind
 *  rdeps
 *  set
 *  union
 * </pre>
 *
 * This query environment will only parse literal targets or the special macro '$declared_deps', so
 * aliases and other patterns (such as ...) will throw an exception. The $declared_deps macro will
 * evaluate to the declared dependencies passed into the constructor.
 */
public class GraphEnhancementQueryEnvironment
    implements QueryEnvironment<ConfiguredQueryTarget>,
        SupportsClasspathEnvironment<ConfiguredQueryTarget> {

  private final Optional<ActionGraphBuilder> graphBuilder;
  private final Optional<TargetGraph> targetGraph;
  private final TypeCoercerFactory typeCoercerFactory;
  private final QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> targetEvaluator;
  private final CellNameResolver cellNameResolver;

  public GraphEnhancementQueryEnvironment(
      Optional<ActionGraphBuilder> graphBuilder,
      Optional<TargetGraph> targetGraph,
      TypeCoercerFactory typeCoercerFactory,
      CellNameResolver cellNames,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      BaseName targetBaseName,
      Set<BuildTarget> declaredDeps,
      TargetConfiguration targetConfiguration) {
    this.graphBuilder = graphBuilder;
    this.targetGraph = targetGraph;
    this.typeCoercerFactory = typeCoercerFactory;
    this.cellNameResolver = cellNames;
    this.targetEvaluator =
        TargetEvaluator.forConfigured(
            cellNames,
            unconfiguredBuildTargetFactory,
            targetBaseName,
            declaredDeps,
            targetConfiguration);
  }

  @Override
  public QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> getTargetEvaluator() {
    return targetEvaluator;
  }

  private Stream<ConfiguredQueryTarget> getFwdDepsStream(Iterable<ConfiguredQueryTarget> targets) {
    return RichStream.from(targets)
        .flatMap(target -> this.getNode(target).getParseDeps().stream())
        .map(ConfiguredQueryBuildTarget::of);
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFwdDeps(Iterable<ConfiguredQueryTarget> targets) {
    return getFwdDepsStream(targets).collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void forEachFwdDep(
      Iterable<ConfiguredQueryTarget> targets, Consumer<ConfiguredQueryTarget> action) {
    getFwdDepsStream(targets).forEach(action);
  }

  @Override
  public Set<ConfiguredQueryTarget> getReverseDeps(Iterable<ConfiguredQueryTarget> targets) {
    Preconditions.checkState(targetGraph.isPresent());
    return StreamSupport.stream(targets.spliterator(), false)
        .map(this::getNode)
        .flatMap(targetNode -> targetGraph.get().getIncomingNodesFor(targetNode).stream())
        .map(node -> ConfiguredQueryBuildTarget.of(node.getBuildTarget()))
        .collect(Collectors.toSet());
  }

  @Override
  public Set<ConfiguredQueryTarget> getInputs(ConfiguredQueryTarget target) {
    TargetNode<?> node = getNode(target);
    return node.getInputs().stream()
        .map(path -> PathSourcePath.of(node.getFilesystem(), path))
        .map(QueryFileTarget::of)
        .collect(Collectors.toSet());
  }

  @Override
  public Set<ConfiguredQueryTarget> getTransitiveClosure(Set<ConfiguredQueryTarget> targets) {
    Preconditions.checkState(targetGraph.isPresent());
    return targetGraph.get()
        .getSubgraph(targets.stream().map(this::getNode).collect(Collectors.toList())).getNodes()
        .stream()
        .map(TargetNode::getBuildTarget)
        .map(ConfiguredQueryBuildTarget::of)
        .collect(Collectors.toSet());
  }

  @Override
  public void buildTransitiveClosure(Set<ConfiguredQueryTarget> targetNodes) {
    // No-op, since the closure should have already been built during parsing
  }

  @Override
  public String getTargetKind(ConfiguredQueryTarget target) {
    return getNode(target).getRuleType().getName();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTestsForTarget(ConfiguredQueryTarget target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getBuildFiles(Set<ConfiguredQueryTarget> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getConfiguredTargets(
      Set<ConfiguredQueryTarget> targets, Optional<String> configuration) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTargetsInAttribute(
      ConfiguredQueryTarget target, ParamName attribute) {
    return QueryTargetAccessor.getTargetsInAttribute(
        typeCoercerFactory, getNode(target), attribute, cellNameResolver);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      ConfiguredQueryTarget target, ParamName attribute, Predicate<Object> predicate) {
    return QueryTargetAccessor.filterAttributeContents(
        typeCoercerFactory, getNode(target), attribute, predicate, cellNameResolver);
  }

  private TargetNode<?> getNode(ConfiguredQueryTarget target) {
    if (!(target instanceof ConfiguredQueryBuildTarget)) {
      throw new IllegalArgumentException(
          String.format(
              "Expected %s to be a build target but it was an instance of %s",
              target, target.getClass().getName()));
    }

    return getNodeForQueryBuildTarget((ConfiguredQueryBuildTarget) target);
  }

  private TargetNode<?> getNodeForQueryBuildTarget(ConfiguredQueryBuildTarget target) {
    Preconditions.checkState(targetGraph.isPresent());
    BuildTarget buildTarget = target.getBuildTarget();
    return targetGraph.get().get(buildTarget);
  }

  @Override
  public Stream<ConfiguredQueryTarget> restrictToInstancesOf(
      Set<ConfiguredQueryTarget> targets, Class<?> clazz) {
    Preconditions.checkArgument(graphBuilder.isPresent());
    return targets.stream()
        .map(
            queryTarget -> {
              Preconditions.checkArgument(queryTarget instanceof ConfiguredQueryBuildTarget);
              return graphBuilder
                  .get()
                  .requireRule(((ConfiguredQueryBuildTarget) queryTarget).getBuildTarget());
            })
        .filter(rule -> clazz.isAssignableFrom(rule.getClass()))
        .map(BuildRule::getBuildTarget)
        .map(ConfiguredQueryBuildTarget::of);
  }

  @Override
  public Stream<ConfiguredQueryTarget> getFirstOrderClasspath(Set<ConfiguredQueryTarget> targets) {
    Preconditions.checkArgument(graphBuilder.isPresent());
    return targets.stream()
        .map(
            queryTarget -> {
              Preconditions.checkArgument(queryTarget instanceof ConfiguredQueryBuildTarget);
              return graphBuilder
                  .get()
                  .requireRule(((ConfiguredQueryBuildTarget) queryTarget).getBuildTarget());
            })
        .filter(rule -> rule instanceof HasClasspathDeps)
        .flatMap(rule -> ((HasClasspathDeps) rule).getDepsForTransitiveClasspathEntries().stream())
        .map(dep -> ConfiguredQueryBuildTarget.of(dep.getBuildTarget()));
  }

  /** The (genericized) set of functions available to this query environment */
  public static <T> Iterable<QueryEnvironment.QueryFunction<T>> defaultFunctions() {
    return ImmutableList.of(
        new AttrFilterFunction<>(),
        new AttrRegexFilterFunction<>(),
        new ClasspathFunction<>(),
        new DepsFunction<>(),
        new DepsFunction.FirstOrderDepsFunction<>(),
        new DepsFunction.LookupFunction<>(),
        new KindFunction<>(),
        new FilterFunction<>(),
        new LabelsFunction<>(),
        new InputsFunction<>(),
        new RdepsFunction<>());
  }

  @Override
  public Iterable<QueryEnvironment.QueryFunction<ConfiguredQueryTarget>> getFunctions() {
    return defaultFunctions();
  }

  /**
   * Implementation of {@link com.facebook.buck.query.QueryEnvironment.TargetEvaluator} for
   * configured target graph.
   */
  public static class TargetEvaluator<TNode, TTarget>
      implements QueryEnvironment.TargetEvaluator<TNode> {
    private final CellNameResolver cellNames;
    private final BaseName targetBaseName;
    private final ImmutableSet<TTarget> declaredDeps;
    private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
    private final Function<UnconfiguredBuildTarget, TTarget> targetFunction;
    private final Function<TTarget, TNode> nodeFunction;

    /** Creates a TargetEvaluator that generates {@code UnconfiguredQueryTarget}s */
    public static TargetEvaluator<UnconfiguredQueryTarget, UnconfiguredBuildTarget> forUnconfigured(
        CellNameResolver cellNames,
        UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
        BaseName targetBaseName,
        Set<UnconfiguredBuildTarget> declaredDeps) {
      return new TargetEvaluator<>(
          cellNames,
          unconfiguredBuildTargetFactory,
          targetBaseName,
          declaredDeps,
          Functions.identity(),
          UnconfiguredQueryBuildTarget::of);
    }

    /** Creates a TargetEvaluator that generates {@code ConfiguredQueryTarget}s */
    public static TargetEvaluator<ConfiguredQueryTarget, BuildTarget> forConfigured(
        CellNameResolver cellNames,
        UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
        BaseName targetBaseName,
        Set<BuildTarget> declaredDeps,
        TargetConfiguration targetConfiguration) {
      return new TargetEvaluator<>(
          cellNames,
          unconfiguredBuildTargetFactory,
          targetBaseName,
          declaredDeps,
          (unconfiguredBuildTarget) -> unconfiguredBuildTarget.configure(targetConfiguration),
          ConfiguredQueryBuildTarget::of);
    }

    private TargetEvaluator(
        CellNameResolver cellNames,
        UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
        BaseName targetBaseName,
        Set<TTarget> declaredDeps,
        Function<UnconfiguredBuildTarget, TTarget> targetFunction,
        Function<TTarget, TNode> nodeFunction) {
      this.cellNames = cellNames;
      this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
      this.targetBaseName = targetBaseName;
      this.declaredDeps = ImmutableSet.copyOf(declaredDeps);
      this.targetFunction = targetFunction;
      this.nodeFunction = nodeFunction;
    }

    @Override
    public ImmutableSet<TNode> evaluateTarget(String target) throws QueryException {
      if ("$declared_deps".equals(target) || "$declared".equals(target)) {
        return declaredDeps.stream()
            .map(nodeFunction::apply)
            .collect(ImmutableSet.toImmutableSet());
      }
      try {
        UnconfiguredBuildTarget unconfiguredBuildTarget =
            unconfiguredBuildTargetFactory.createForBaseName(targetBaseName, target, cellNames);
        TTarget genericBuildTarget = targetFunction.apply(unconfiguredBuildTarget);
        return ImmutableSet.of(nodeFunction.apply(genericBuildTarget));
      } catch (BuildTargetParseException e) {
        throw new QueryException(e, "Unable to parse pattern %s", target);
      }
    }

    @Override
    public QueryEnvironment.TargetEvaluator.Type getType() {
      return Type.IMMEDIATE;
    }
  }
}
