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

import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.query.QueryTargetAccessor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A query environment that can be used for graph-enhancement, including macro expansion or
 * dynamic dependency resolution.
 *
 * The query environment supports the following functions
 * <pre>
 *  attrfilter
 *  deps
 *  inputs
 *  except
 *  intersect
 *  filter
 *  kind
 *  set
 *  union
 * </pre>
 *
 * This query environment will only parse literal targets or the special macro
 * '$declared_deps', so aliases and other patterns (such as ...) will throw an exception.
 * The $declared_deps macro will evaluate to the declared dependencies passed into the constructor.
 */
public class GraphEnhancementQueryEnvironment implements QueryEnvironment {

  private Optional<BuildRuleResolver> resolver;
  private Optional<TargetGraph> targetGraph;
  private CellPathResolver cellNames;
  private BuildTarget context;
  private Set<BuildTarget> declaredDeps;

  public GraphEnhancementQueryEnvironment(
      Optional<BuildRuleResolver> resolver,
      Optional<TargetGraph> targetGraph,
      CellPathResolver cellNames,
      BuildTarget context,
      Set<BuildTarget> declaredDeps) {
    this.resolver = resolver;
    this.targetGraph = targetGraph;
    this.cellNames = cellNames;
    this.context = context;
    this.declaredDeps = declaredDeps;
  }

  @Override
  public Set<QueryTarget> getTargetsMatchingPattern(
      String pattern,
      ListeningExecutorService executor) throws QueryException, InterruptedException {
    if ("$declared_deps".equals(pattern)) {
      return declaredDeps
          .stream()
          .map(QueryBuildTarget::of)
          .collect(Collectors.toSet());
    }
    try {
      BuildTarget buildTarget = BuildTargetParser.INSTANCE.parse(
          pattern,
          BuildTargetPatternParser.forBaseName(context.getBaseName()),
          cellNames);
      return ImmutableSet.<QueryTarget>of(QueryBuildTarget.of(buildTarget));
    } catch (BuildTargetParseException e) {
      throw new QueryException(e.getMessage(), e);
    }
  }

  @Override
  public Set<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    ImmutableSet.Builder<QueryTarget> builder = ImmutableSet.builder();
    for (QueryTarget target : targets) {
      List<QueryBuildTarget> deps = getNode(target)
          .getDeps()
          .stream()
          .map(QueryBuildTarget::of)
          .collect(Collectors.toList());
      builder.addAll(deps);
    }
    return builder.build();
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<QueryTarget> getInputs(QueryTarget target) throws QueryException {
    TargetNode<?, ?> node = getNode(target);
    return node.getInputs().stream()
        .map(QueryFileTarget::of)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public Set<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets)
      throws QueryException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void buildTransitiveClosure(
      Set<QueryTarget> targetNodes,
      int maxDepth,
      ListeningExecutorService executor) throws InterruptedException, QueryException {
    // No-op, since the closure should have already been built during parsing
  }

  @Override
  public String getTargetKind(QueryTarget target) throws InterruptedException, QueryException {
    return getNode(target).getType().getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target)
      throws InterruptedException, QueryException {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) throws QueryException {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(
      ImmutableList<String> files,
      ListeningExecutorService executor) throws InterruptedException, QueryException {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(
      QueryTarget target, String attribute) throws InterruptedException, QueryException {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target,
      String attribute,
      Predicate<Object> predicate) throws InterruptedException, QueryException {
    return QueryTargetAccessor.filterAttributeContents(getNode(target), attribute, predicate);
  }

  private TargetNode<?, ?> getNode(QueryTarget target) {
    Preconditions.checkState(target instanceof QueryBuildTarget);
    Preconditions.checkArgument(targetGraph.isPresent());
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return targetGraph.get().getOptional(buildTarget).orElse(null);
  }

  public ImmutableSet<QueryTarget> getClasspath(Set<QueryTarget> targets) {
    Preconditions.checkArgument(resolver.isPresent());
    return ImmutableSet.copyOf(targets.stream()
        .map(queryTarget -> {
          Preconditions.checkArgument(queryTarget instanceof QueryBuildTarget);
          return resolver.get().getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
        })
        .filter(rule -> rule instanceof HasClasspathEntries)
        .map(rule -> (HasClasspathEntries) rule)
        .flatMap(hasClasspathEntries -> hasClasspathEntries.getTransitiveClasspathDeps().stream())
        .map(dep -> QueryBuildTarget.of(dep.getBuildTarget()))
        .collect(Collectors.toSet()));
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return ImmutableSet.of(
        new AttrFilterFunction(),
        new ClasspathFunction(),
        new DepsFunction(),
        new KindFunction(),
        new FilterFunction()
    );
  }
}
