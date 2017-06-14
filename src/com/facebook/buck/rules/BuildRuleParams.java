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
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;

/** Standard set of parameters that is passed to all build rules. */
public class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final Optional<? extends SortedSet<BuildRule>> declaredDeps;
  private final Optional<? extends SortedSet<BuildRule>> extraDeps;
  private final ImmutableSortedSet<BuildRule> targetGraphOnlyDeps;
  private final ProjectFilesystem projectFilesystem;

  public BuildRuleParams(
      BuildTarget buildTarget,
      SortedSet<BuildRule> declaredDeps,
      SortedSet<BuildRule> extraDeps,
      ImmutableSortedSet<BuildRule> targetGraphOnlyDeps,
      ProjectFilesystem projectFilesystem) {
    this(
        buildTarget,
        Optional.of(declaredDeps),
        Optional.of(extraDeps),
        targetGraphOnlyDeps,
        projectFilesystem);
  }

  private BuildRuleParams(
      BuildTarget buildTarget,
      Optional<? extends SortedSet<BuildRule>> declaredDeps,
      Optional<? extends SortedSet<BuildRule>> extraDeps,
      ImmutableSortedSet<BuildRule> targetGraphOnlyDeps,
      ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.declaredDeps = declaredDeps;
    this.extraDeps = extraDeps;
    this.projectFilesystem = projectFilesystem;
    this.targetGraphOnlyDeps = targetGraphOnlyDeps;
  }

  public BuildRuleParams copyReplacingExtraDeps(ImmutableSortedSet<BuildRule> newExtraDeps) {
    return new BuildRuleParams(
        buildTarget,
        declaredDeps,
        Optional.of(newExtraDeps),
        targetGraphOnlyDeps,
        projectFilesystem);
  }

  /**
   * @return a copy of these {@link BuildRuleParams} with the deps removed to prevent using them to
   *     as the deps in constructed {@link BuildRule}s.
   */
  public BuildRuleParams copyInvalidatingDeps() {
    return new BuildRuleParams(
        buildTarget, Optional.empty(), Optional.empty(), targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildRuleParams copyAppendingExtraDeps(Iterable<? extends BuildRule> additional) {
    return new BuildRuleParams(
        buildTarget,
        declaredDeps,
        Optional.of(SortedSets.union(extraDeps.get(), ImmutableSortedSet.copyOf(additional))),
        targetGraphOnlyDeps,
        projectFilesystem);
  }

  public BuildRuleParams copyAppendingExtraDeps(BuildRule... additional) {
    return copyAppendingExtraDeps(ImmutableSortedSet.copyOf(additional));
  }

  public BuildRuleParams copyReplacingDeclaredAndExtraDeps(
      SortedSet<BuildRule> newDeclaredDeps, SortedSet<BuildRule> newExtraDeps) {
    return new BuildRuleParams(
        buildTarget, newDeclaredDeps, newExtraDeps, targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildRuleParams copyReplacingDeclaredDeps(SortedSet<BuildRule> newDeclaredDeps) {
    return new BuildRuleParams(
        buildTarget,
        Optional.of(newDeclaredDeps),
        extraDeps,
        targetGraphOnlyDeps,
        projectFilesystem);
  }

  public BuildRuleParams withBuildTarget(BuildTarget newTarget) {
    return new BuildRuleParams(
        newTarget, declaredDeps, extraDeps, targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildRuleParams withoutFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.remove(flavor);
    BuildTarget newTarget =
        BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .build();

    return new BuildRuleParams(
        newTarget, declaredDeps, extraDeps, targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildRuleParams withAppendedFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.add(flavor);
    BuildTarget newTarget =
        BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .build();

    return new BuildRuleParams(
        newTarget, declaredDeps, extraDeps, targetGraphOnlyDeps, projectFilesystem);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  /** @return all BuildRules which must be built before this one can be. */
  public SortedSet<BuildRule> getBuildDeps() {
    return SortedSets.union(getDeclaredDeps().get(), getExtraDeps().get());
  }

  public Supplier<SortedSet<BuildRule>> getDeclaredDeps() {
    return () -> declaredDeps.orElseThrow(this::invalidDepsException);
  }

  public Supplier<SortedSet<BuildRule>> getExtraDeps() {
    return () -> extraDeps.orElseThrow(this::invalidDepsException);
  }

  private IllegalStateException invalidDepsException() {
    return new IllegalStateException(
        String.format(
            "%s: Access to target-node level `BuildRuleParam` deps. "
                + "Please compose application-specific deps from the constructor arg instead.",
            getBuildTarget()));
  }

  /** See {@link TargetNode#getTargetGraphOnlyDeps}. */
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }
}
