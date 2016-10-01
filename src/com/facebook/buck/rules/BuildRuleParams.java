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
import com.facebook.buck.model.Flavor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Standard set of parameters that is passed to all build rules.
 */
public class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> declaredDeps;
  private final ImmutableSortedSet<BuildRule> extraDeps;
  private final ImmutableSortedSet<BuildRule> totalDeps;
  private final ProjectFilesystem projectFilesystem;
  private final CellPathResolver cellRoots;

  public BuildRuleParams(
      BuildTarget buildTarget,
      final ImmutableSortedSet<BuildRule> declaredDeps,
      final ImmutableSortedSet<BuildRule> extraDeps,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellRoots) {
    this.buildTarget = buildTarget;
    this.declaredDeps = declaredDeps;
    this.extraDeps = extraDeps;
    this.projectFilesystem = projectFilesystem;
    this.cellRoots = cellRoots;

    this.totalDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(declaredDeps)
        .addAll(extraDeps)
        .build();
  }

  public BuildRuleParams copyWithExtraDeps(ImmutableSortedSet<BuildRule> extraDeps) {
    return copyWithDeps(declaredDeps, extraDeps);
  }

  public BuildRuleParams appendExtraDeps(Iterable<? extends BuildRule> additional) {
    return copyWithDeps(
        declaredDeps,
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(extraDeps)
            .addAll(additional)
            .build());
  }

  public BuildRuleParams appendExtraDeps(BuildRule... additional) {
    return appendExtraDeps(ImmutableList.copyOf(additional));
  }

  public BuildRuleParams copyWithDeps(
      ImmutableSortedSet<BuildRule> declaredDeps,
      ImmutableSortedSet<BuildRule> extraDeps) {
    return copyWithChanges(buildTarget, declaredDeps, extraDeps);
  }

  public BuildRuleParams copyWithBuildTarget(BuildTarget target) {
    return copyWithChanges(target, declaredDeps, extraDeps);
  }

  public BuildRuleParams copyWithChanges(
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> declaredDeps,
      ImmutableSortedSet<BuildRule> extraDeps) {
    return new BuildRuleParams(
        buildTarget,
        declaredDeps,
        extraDeps,
        projectFilesystem,
        cellRoots);
  }

  public BuildRuleParams withoutFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.remove(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return copyWithChanges(
        target,
        declaredDeps,
        extraDeps);
  }

  public BuildRuleParams withFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.add(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return copyWithChanges(
        target,
        declaredDeps,
        extraDeps);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public CellPathResolver getCellRoots() {
    return cellRoots;
  }

  public ImmutableSortedSet<BuildRule> getDeps() {
    return totalDeps;
  }

  public ImmutableSortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps;
  }

  public ImmutableSortedSet<BuildRule> getExtraDeps() {
    return extraDeps;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

}
