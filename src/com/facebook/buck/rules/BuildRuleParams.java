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
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Set;

/** Standard set of parameters that is passed to all build rules. */
public class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> extraDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> totalBuildDeps;
  private final ImmutableSortedSet<BuildRule> targetGraphOnlyDeps;
  private final ProjectFilesystem projectFilesystem;

  public BuildRuleParams(
      BuildTarget buildTarget,
      final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      final Supplier<ImmutableSortedSet<BuildRule>> extraDeps,
      ImmutableSortedSet<BuildRule> targetGraphOnlyDeps,
      ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.declaredDeps = Suppliers.memoize(declaredDeps);
    this.extraDeps = Suppliers.memoize(extraDeps);
    this.projectFilesystem = projectFilesystem;
    this.targetGraphOnlyDeps = targetGraphOnlyDeps;

    this.totalBuildDeps =
        Suppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(declaredDeps.get())
                    .addAll(extraDeps.get())
                    .build());
  }

  private BuildRuleParams(
      BuildRuleParams baseForDeps, BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.declaredDeps = baseForDeps.declaredDeps;
    this.extraDeps = baseForDeps.extraDeps;
    this.targetGraphOnlyDeps = baseForDeps.targetGraphOnlyDeps;
    this.totalBuildDeps = baseForDeps.totalBuildDeps;
  }

  public BuildRuleParams copyReplacingExtraDeps(Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return copyReplacingDeclaredAndExtraDeps(declaredDeps, extraDeps);
  }

  public BuildRuleParams copyAppendingExtraDeps(
      final Supplier<? extends Iterable<? extends BuildRule>> additional) {
    return copyReplacingDeclaredAndExtraDeps(
        declaredDeps,
        () ->
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(extraDeps.get())
                .addAll(additional.get())
                .build());
  }

  /**
   * @return a copy of these {@link BuildRuleParams} with the deps removed to prevent using them to
   *     as the deps in constructed {@link BuildRule}s.
   */
  public BuildRuleParams copyInvalidatingDeps() {
    Supplier<ImmutableSortedSet<BuildRule>> throwingDeps =
        () -> {
          throw new IllegalStateException(
              String.format(
                  "%s: Access to target-node level `BuildRuleParam` deps. "
                      + "Please compose application-specific deps from the constructor arg instead.",
                  getBuildTarget()));
        };
    return copyReplacingDeclaredAndExtraDeps(throwingDeps, throwingDeps);
  }

  public BuildRuleParams copyAppendingExtraDeps(Iterable<? extends BuildRule> additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(additional));
  }

  public BuildRuleParams copyAppendingExtraDeps(BuildRule... additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(ImmutableList.copyOf(additional)));
  }

  public BuildRuleParams copyReplacingDeclaredAndExtraDeps(
      Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return new BuildRuleParams(
        buildTarget, declaredDeps, extraDeps, targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildRuleParams withBuildTarget(BuildTarget target) {
    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildRuleParams withoutFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.remove(flavor);
    BuildTarget target =
        BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .build();

    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildRuleParams withAppendedFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.add(flavor);
    BuildTarget target =
        BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .build();

    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  /** @return all BuildRules which must be built before this one can be. */
  public ImmutableSortedSet<BuildRule> getBuildDeps() {
    return totalBuildDeps.get();
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getTotalBuildDeps() {
    return totalBuildDeps;
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getDeclaredDeps() {
    return declaredDeps;
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getExtraDeps() {
    return extraDeps;
  }

  /** See {@link TargetNode#getTargetGraphOnlyDeps}. */
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }
}
