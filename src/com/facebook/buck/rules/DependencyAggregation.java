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
package com.facebook.buck.rules;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import javax.annotation.Nullable;

/**
 * A noop build rule used to aggregate dependencies shared amongst many rules.
 *
 * <p>In cases where many rules require largely the same sets of dependencies, such as cxx
 * compilation of files in a target, explicitly copying the dependencies to every such rule imposes
 * slow down and memory usage proportional to the number of rules that share a dependency set. This
 * class curtails the copying of all shared dependencies between rules, and instead allow each rule
 * to depend on this single rule, which captures the shared dependencies.
 *
 * <p>This class is distinct from {@link NoopBuildRuleWithDeclaredAndExtraDeps} to make clear the
 * requirements for its operation, namely, that it cannot be cached. This rule must not be cached in
 * order for its dependencies to always be evaluated in different build strategies (in particular,
 * top-down).
 */
public final class DependencyAggregation extends AbstractBuildRule {

  private final ImmutableSortedSet<BuildRule> deps;

  public DependencyAggregation(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps) {
    super(buildTarget, projectFilesystem);
    this.deps = deps;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return deps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
