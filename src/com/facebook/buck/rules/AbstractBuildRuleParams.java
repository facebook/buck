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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.util.Set;

/**
 * Standard set of parameters that is passed to all build rules.
 */
@BuckStyleImmutable
@Value.Immutable
abstract class AbstractBuildRuleParams {

  public abstract BuildTarget getBuildTarget();

  public abstract CellPathResolver getCellRoots();

  public abstract Supplier<ImmutableSortedSet<BuildRule>> getDeclaredDeps();

  public abstract Supplier<ImmutableSortedSet<BuildRule>> getExtraDeps();

  public abstract ProjectFilesystem getProjectFilesystem();


  public BuildRuleParams appendExtraDep(BuildRule additional) {
    return appendExtraDeps(Suppliers.ofInstance(ImmutableList.of(additional)));
  }

  public BuildRuleParams appendExtraDeps(Iterable<? extends BuildRule> additional) {
    return appendExtraDeps(Suppliers.ofInstance(additional));
  }

  public BuildRuleParams appendExtraDeps(
      final Supplier<? extends Iterable<? extends BuildRule>> additional) {
    return BuildRuleParams.copyOf(this).withExtraDeps(
        () -> ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(getExtraDeps().get())
            .addAll(additional.get())
            .build());
  }

  public BuildRuleParams withoutDeclaredDeps() {
    return BuildRuleParams.copyOf(this)
        .withDeclaredDeps(Suppliers.ofInstance(ImmutableSortedSet.of()));
  }

  public BuildRuleParams withoutExtraDeps() {
    return BuildRuleParams.copyOf(this)
        .withExtraDeps(Suppliers.ofInstance(ImmutableSortedSet.of()));
  }

  public BuildRuleParams withoutFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.remove(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return BuildRuleParams.copyOf(this).withBuildTarget(target);
  }

  public BuildRuleParams withFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.add(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return BuildRuleParams.copyOf(this).withBuildTarget(target);
  }

  public ImmutableSortedSet<BuildRule> getDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getDeclaredDeps().get())
        .addAll(getExtraDeps().get())
        .build();
  }

}
