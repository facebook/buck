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

import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.function.Supplier;

/** Standard set of parameters that is passed to all build rules. */
public class BuildRuleParams {

  private final Supplier<? extends SortedSet<BuildRule>> declaredDeps;
  private final Supplier<? extends SortedSet<BuildRule>> extraDeps;
  private final Supplier<SortedSet<BuildRule>> totalBuildDeps;
  private final ImmutableSortedSet<BuildRule> targetGraphOnlyDeps;

  public BuildRuleParams(
      Supplier<? extends SortedSet<BuildRule>> declaredDeps,
      Supplier<? extends SortedSet<BuildRule>> extraDeps,
      ImmutableSortedSet<BuildRule> targetGraphOnlyDeps) {
    this.declaredDeps = MoreSuppliers.memoize(declaredDeps);
    this.extraDeps = MoreSuppliers.memoize(extraDeps);
    this.targetGraphOnlyDeps = targetGraphOnlyDeps;

    this.totalBuildDeps =
        MoreSuppliers.memoize(
            () -> SortedSets.union(this.declaredDeps.get(), this.extraDeps.get()));
  }

  public BuildRuleParams withDeclaredDeps(SortedSet<BuildRule> declaredDeps) {
    return withDeclaredDeps(() -> declaredDeps);
  }

  public BuildRuleParams withDeclaredDeps(Supplier<? extends SortedSet<BuildRule>> declaredDeps) {
    return new BuildRuleParams(declaredDeps, extraDeps, targetGraphOnlyDeps);
  }

  public BuildRuleParams withoutDeclaredDeps() {
    return withDeclaredDeps(ImmutableSortedSet.of());
  }

  public BuildRuleParams withExtraDeps(Supplier<? extends SortedSet<BuildRule>> extraDeps) {
    return new BuildRuleParams(declaredDeps, extraDeps, targetGraphOnlyDeps);
  }

  public BuildRuleParams withExtraDeps(SortedSet<BuildRule> extraDeps) {
    return withExtraDeps(() -> extraDeps);
  }

  public BuildRuleParams copyAppendingExtraDeps(
      Supplier<? extends Iterable<? extends BuildRule>> additional) {
    Supplier<? extends SortedSet<BuildRule>> extraDeps1 =
        () ->
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(extraDeps.get())
                .addAll(additional.get())
                .build();
    return withDeclaredDeps(declaredDeps).withExtraDeps(extraDeps1);
  }

  public BuildRuleParams copyAppendingExtraDeps(Iterable<? extends BuildRule> additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(additional));
  }

  public BuildRuleParams copyAppendingExtraDeps(BuildRule... additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(ImmutableList.copyOf(additional)));
  }

  public BuildRuleParams withoutExtraDeps() {
    return withExtraDeps(ImmutableSortedSet.of());
  }

  /** @return all BuildRules which must be built before this one can be. */
  public SortedSet<BuildRule> getBuildDeps() {
    return totalBuildDeps.get();
  }

  public Supplier<? extends SortedSet<BuildRule>> getDeclaredDeps() {
    return declaredDeps;
  }

  public Supplier<? extends SortedSet<BuildRule>> getExtraDeps() {
    return extraDeps;
  }

  /** See {@link TargetNode#getTargetGraphOnlyDeps}. */
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }
}
