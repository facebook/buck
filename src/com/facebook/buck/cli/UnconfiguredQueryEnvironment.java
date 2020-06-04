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
import com.facebook.buck.parser.Parser;
import com.facebook.buck.query.AllPathsFunction;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.BuildFileFunction;
import com.facebook.buck.query.ConfiguredQueryTarget;
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
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** QueryEnvironment implementation that operates over the unconfigured target graph. */
@SuppressWarnings("unused")
public class UnconfiguredQueryEnvironment
    implements EvaluatingQueryEnvironment<ConfiguredQueryTarget> {

  private final Parser parser;
  private final Cell rootCell;

  public UnconfiguredQueryEnvironment(Parser parser, Cell rootCell) {
    this.parser = parser;
    this.rootCell = rootCell;
  }

  public static UnconfiguredQueryEnvironment from(CommandRunnerParams params) {
    return new UnconfiguredQueryEnvironment(params.getParser(), params.getCells().getRootCell());
  }

  @Override
  public Set<ConfiguredQueryTarget> evaluateQuery(QueryExpression<ConfiguredQueryTarget> expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator<ConfiguredQueryTarget>().eval(expr, this);
  }

  @Override
  public void preloadTargetPatterns(Iterable<String> patterns)
      throws QueryException, InterruptedException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public Iterable<QueryFunction<ConfiguredQueryTarget>> getFunctions() {
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
  public ImmutableSet<ConfiguredQueryTarget> getTransitiveClosure(
      Set<ConfiguredQueryTarget> targets) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public void buildTransitiveClosure(Set<ConfiguredQueryTarget> targets) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> getTargetEvaluator() {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFwdDeps(Iterable<ConfiguredQueryTarget> targets)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public Set<ConfiguredQueryTarget> getReverseDeps(Iterable<ConfiguredQueryTarget> targets)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public Set<ConfiguredQueryTarget> getInputs(ConfiguredQueryTarget target) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTestsForTarget(ConfiguredQueryTarget target)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getBuildFiles(Set<ConfiguredQueryTarget> targets) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getConfiguredTargets(
      Set<ConfiguredQueryTarget> targets, Optional<String> configurationName)
      throws QueryException {
    throw new UnsupportedOperationException(
        "Calls to `getConfiguredTargets` are not valid for UnconfiguredQueryEnvironment");
  }

  @Override
  public String getTargetKind(ConfiguredQueryTarget target) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTargetsInAttribute(
      ConfiguredQueryTarget target, ParamName attribute) throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      ConfiguredQueryTarget target, ParamName attribute, Predicate<Object> predicate)
      throws QueryException {
    throw new RuntimeException("Not yet implemented");
  }
}
