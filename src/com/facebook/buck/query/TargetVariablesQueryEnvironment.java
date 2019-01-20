/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.query;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Provides a view of an existing {@link QueryEnvironment} augmented with additional target
 * variables.
 */
public class TargetVariablesQueryEnvironment implements QueryEnvironment {

  private final ImmutableMap<String, ImmutableSet<QueryTarget>> targetVariables;
  private final QueryEnvironment delegate;

  public TargetVariablesQueryEnvironment(
      ImmutableMap<String, ImmutableSet<QueryTarget>> targetVariables, QueryEnvironment delegate) {
    this.targetVariables = targetVariables;
    this.delegate = delegate;
  }

  @Override
  public TargetEvaluator getTargetEvaluator() {
    return delegate.getTargetEvaluator();
  }

  @Override
  public ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) throws QueryException {
    return delegate.getFwdDeps(targets);
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) throws QueryException {
    return delegate.getReverseDeps(targets);
  }

  @Override
  public Set<QueryTarget> getInputs(QueryTarget target) throws QueryException {
    return delegate.getInputs(target);
  }

  @Override
  public Set<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets) throws QueryException {
    return delegate.getTransitiveClosure(targets);
  }

  @Override
  public void buildTransitiveClosure(Set<QueryTarget> targetNodes, int maxDepth)
      throws QueryException {
    delegate.buildTransitiveClosure(targetNodes, maxDepth);
  }

  @Override
  public String getTargetKind(QueryTarget target) throws QueryException {
    return delegate.getTargetKind(target);
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target) throws QueryException {
    return delegate.getTestsForTarget(target);
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) throws QueryException {
    return delegate.getBuildFiles(targets);
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files)
      throws QueryException {
    return delegate.getFileOwners(files);
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException {
    return delegate.getTargetsInAttribute(target, attribute);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, Predicate<Object> predicate) throws QueryException {
    return delegate.filterAttributeContents(target, attribute, predicate);
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return delegate.getFunctions();
  }

  @Override
  public ImmutableSet<QueryTarget> resolveTargetVariable(String name) {
    ImmutableSet<QueryTarget> targets = targetVariables.get(name);
    if (targets != null) {
      return targets;
    }
    return delegate.resolveTargetVariable(name);
  }
}
