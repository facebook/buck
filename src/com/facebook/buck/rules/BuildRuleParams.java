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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Standard set of parameters that is passed to all build rules.
 */
@Beta
public class BuildRuleParams implements BuildableParams {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> declaredDeps;
  private final ImmutableSortedSet<BuildRule> extraDeps;
  private final ImmutableSortedSet<BuildRule> totalDeps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;
  private final ProjectFilesystem projectFilesystem;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  private final BuildRuleType buildRuleType;

  public BuildRuleParams(
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> declaredDeps,
      ImmutableSortedSet<BuildRule> extraDeps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleType buildRuleType) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.declaredDeps = Preconditions.checkNotNull(declaredDeps);
    this.extraDeps = Preconditions.checkNotNull(extraDeps);
    this.visibilityPatterns = Preconditions.checkNotNull(visibilityPatterns);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.ruleKeyBuilderFactory = Preconditions.checkNotNull(ruleKeyBuilderFactory);
    this.buildRuleType = Preconditions.checkNotNull(buildRuleType);

    this.totalDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(declaredDeps)
        .addAll(extraDeps)
        .build();
  }

  public BuildRuleParams copyWithExtraDeps(ImmutableSortedSet<BuildRule> extraDeps) {
    return copyWithDeps(declaredDeps, extraDeps);
  }

  public BuildRuleParams copyWithDeps(
      ImmutableSortedSet<BuildRule> declaredDeps,
      ImmutableSortedSet<BuildRule> extraDeps) {
    return copyWithChanges(buildRuleType, buildTarget, declaredDeps, extraDeps);
  }

  public BuildRuleParams copyWithChanges(
      BuildRuleType buildRuleType,
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> declaredDeps,
      ImmutableSortedSet<BuildRule> extraDeps) {
    return new BuildRuleParams(
        buildTarget,
        declaredDeps,
        extraDeps,
        visibilityPatterns,
        projectFilesystem,
        ruleKeyBuilderFactory,
        buildRuleType);
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDeps() {
    return totalDeps;
  }

  public ImmutableSortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps;
  }

  public ImmutableSortedSet<BuildRule> getExtraDeps() {
    return extraDeps;
  }

  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return visibilityPatterns;
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
}
