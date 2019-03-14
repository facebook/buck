/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.query.QueryTargetAccessor;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
public class GraphEnhancementQueryEnvironment implements QueryEnvironment {

  private final Optional<ActionGraphBuilder> graphBuilder;
  private final Optional<TargetGraph> targetGraph;
  private final TypeCoercerFactory typeCoercerFactory;
  private final QueryEnvironment.TargetEvaluator targetEvaluator;

  public GraphEnhancementQueryEnvironment(
      Optional<ActionGraphBuilder> graphBuilder,
      Optional<TargetGraph> targetGraph,
      TypeCoercerFactory typeCoercerFactory,
      CellPathResolver cellNames,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      String targetBaseName,
      Set<BuildTarget> declaredDeps) {
    this.graphBuilder = graphBuilder;
    this.targetGraph = targetGraph;
    this.typeCoercerFactory = typeCoercerFactory;
    this.targetEvaluator =
        new TargetEvaluator(
            cellNames, unconfiguredBuildTargetFactory, targetBaseName, declaredDeps);
  }

  @Override
  public QueryEnvironment.TargetEvaluator getTargetEvaluator() {
    return targetEvaluator;
  }

  private Stream<QueryTarget> getFwdDepsStream(Iterable<QueryTarget> targets) {
    return RichStream.from(targets)
        .flatMap(target -> this.getNode(target).getParseDeps().stream())
        .map(QueryBuildTarget::of);
  }

  @Override
  public ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) {
    return getFwdDepsStream(targets).collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void forEachFwdDep(Iterable<QueryTarget> targets, Consumer<? super QueryTarget> action) {
    getFwdDepsStream(targets).forEach(action);
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) {
    Preconditions.checkState(targetGraph.isPresent());
    return StreamSupport.stream(targets.spliterator(), false)
        .map(this::getNode)
        .flatMap(targetNode -> targetGraph.get().getIncomingNodesFor(targetNode).stream())
        .map(node -> QueryBuildTarget.of(node.getBuildTarget()))
        .collect(Collectors.toSet());
  }

  @Override
  public Set<QueryTarget> getInputs(QueryTarget target) {
    TargetNode<?> node = getNode(target);
    return node.getInputs()
        .stream()
        .map(path -> PathSourcePath.of(node.getFilesystem(), path))
        .map(QueryFileTarget::of)
        .collect(Collectors.toSet());
  }

  @Override
  public Set<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets) {
    Preconditions.checkState(targetGraph.isPresent());
    return targetGraph
        .get()
        .getSubgraph(targets.stream().map(this::getNode).collect(Collectors.toList()))
        .getNodes()
        .stream()
        .map(TargetNode::getBuildTarget)
        .map(QueryBuildTarget::of)
        .collect(Collectors.toSet());
  }

  @Override
  public void buildTransitiveClosure(Set<QueryTarget> targetNodes, int maxDepth) {
    // No-op, since the closure should have already been built during parsing
  }

  @Override
  public String getTargetKind(QueryTarget target) {
    return getNode(target).getRuleType().getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute) {
    return QueryTargetAccessor.getTargetsInAttribute(
        typeCoercerFactory, getNode(target), attribute);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, Predicate<Object> predicate) {
    return QueryTargetAccessor.filterAttributeContents(
        typeCoercerFactory, getNode(target), attribute, predicate);
  }

  private TargetNode<?> getNode(QueryTarget target) {
    Preconditions.checkState(target instanceof QueryBuildTarget);
    Preconditions.checkArgument(targetGraph.isPresent());
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return targetGraph.get().get(buildTarget);
  }

  public Stream<QueryTarget> getFirstOrderClasspath(Set<QueryTarget> targets) {
    Preconditions.checkArgument(graphBuilder.isPresent());
    return targets
        .stream()
        .map(
            queryTarget -> {
              Preconditions.checkArgument(queryTarget instanceof QueryBuildTarget);
              return graphBuilder
                  .get()
                  .requireRule(((QueryBuildTarget) queryTarget).getBuildTarget());
            })
        .filter(rule -> rule instanceof HasClasspathDeps)
        .flatMap(rule -> ((HasClasspathDeps) rule).getDepsForTransitiveClasspathEntries().stream())
        .map(dep -> QueryBuildTarget.of(dep.getBuildTarget()));
  }

  public static final Iterable<QueryEnvironment.QueryFunction> QUERY_FUNCTIONS =
      ImmutableList.of(
          new AttrFilterFunction(),
          new ClasspathFunction(),
          new DepsFunction(),
          new DepsFunction.FirstOrderDepsFunction(),
          new DepsFunction.LookupFunction(),
          new KindFunction(),
          new FilterFunction(),
          new LabelsFunction(),
          new InputsFunction(),
          new RdepsFunction());

  @Override
  public Iterable<QueryEnvironment.QueryFunction> getFunctions() {
    return QUERY_FUNCTIONS;
  }

  private static class TargetEvaluator implements QueryEnvironment.TargetEvaluator {
    private final CellPathResolver cellNames;
    private final String targetBaseName;
    private final ImmutableSet<BuildTarget> declaredDeps;
    private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;

    private TargetEvaluator(
        CellPathResolver cellNames,
        UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
        String targetBaseName,
        Set<BuildTarget> declaredDeps) {
      this.cellNames = cellNames;
      this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
      this.targetBaseName = targetBaseName;
      this.declaredDeps = ImmutableSet.copyOf(declaredDeps);
    }

    @Override
    public ImmutableSet<QueryTarget> evaluateTarget(String target) throws QueryException {
      if ("$declared_deps".equals(target) || "$declared".equals(target)) {
        return declaredDeps
            .stream()
            .map(QueryBuildTarget::of)
            .collect(ImmutableSet.toImmutableSet());
      }
      try {
        BuildTarget buildTarget =
            unconfiguredBuildTargetFactory
                .createForBaseName(cellNames, targetBaseName, target)
                .configure(EmptyTargetConfiguration.INSTANCE);
        return ImmutableSet.of(QueryBuildTarget.of(buildTarget));
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
