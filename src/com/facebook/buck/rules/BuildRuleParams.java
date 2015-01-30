/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Standard set of parameters that is passed to all build rules.
 */
@Beta
public class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> extraDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> totalDeps;
  private final ProjectFilesystem projectFilesystem;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  private final BuildRuleType buildRuleType;
  private final TargetGraph targetGraph;

  public BuildRuleParams(
      BuildTarget buildTarget,
      final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      final Supplier<ImmutableSortedSet<BuildRule>> extraDeps,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleType buildRuleType,
      TargetGraph targetGraph) {
    this.buildTarget = buildTarget;
    this.declaredDeps = Suppliers.memoize(declaredDeps);
    this.extraDeps = Suppliers.memoize(extraDeps);
    this.projectFilesystem = projectFilesystem;
    this.ruleKeyBuilderFactory = ruleKeyBuilderFactory;
    this.buildRuleType = buildRuleType;
    this.targetGraph = targetGraph;

    this.totalDeps = Suppliers.memoize(
        new Supplier<ImmutableSortedSet<BuildRule>>() {

          @Override
          public ImmutableSortedSet<BuildRule> get() {
            return ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(declaredDeps.get())
                .addAll(extraDeps.get())
                .build();
          }
        });
  }

  public BuildRuleParams copyWithExtraDeps(Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return copyWithDeps(declaredDeps, extraDeps);
  }

  public BuildRuleParams appendExtraDeps(final Supplier<Iterable<BuildRule>> additional) {
    return copyWithDeps(
        declaredDeps,
        new Supplier<ImmutableSortedSet<BuildRule>>() {

          @Override
          public ImmutableSortedSet<BuildRule> get() {
            return ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(extraDeps.get())
                .addAll(additional.get())
                .build();
          }
        });
  }

  public BuildRuleParams copyWithDeps(
      Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return copyWithChanges(buildRuleType, buildTarget, declaredDeps, extraDeps);
  }

  public BuildRuleParams copyWithChanges(
      BuildRuleType buildRuleType,
      BuildTarget buildTarget,
      Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return new BuildRuleParams(
        buildTarget,
        declaredDeps,
        extraDeps,
        projectFilesystem,
        ruleKeyBuilderFactory,
        buildRuleType,
        targetGraph);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public ImmutableSortedSet<BuildRule> getDeps() {
    return totalDeps.get();
  }

  public ImmutableSortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps.get();
  }

  public ImmutableSortedSet<BuildRule> getExtraDeps() {
    return extraDeps.get();
  }

  public Function<Path, Path> getPathAbsolutifier() {
    return projectFilesystem.getAbsolutifier();
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
    return ruleKeyBuilderFactory;
  }

  public BuildRuleType getBuildRuleType() {
    return buildRuleType;
  }

  public TargetGraph getTargetGraph() {
    return targetGraph;
  }
}
