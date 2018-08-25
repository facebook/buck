/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.collect.ImmutableSortedSet;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.SortedSet;
import javax.annotation.Nullable;

/**
 * Represents the build deps of a rule in a way that allows rule dependencies to extend one another,
 * as for pipelining.
 */
public class BuildDeps extends AbstractSet<BuildRule>
    implements AddsToRuleKey, SortedSet<BuildRule> {
  @AddToRuleKey @Nullable private final BuildDeps previousRuleBuildDeps;

  @AddToRuleKey private final ImmutableSortedSet<BuildRule> additionalDeps;

  private final ImmutableSortedSet<BuildRule> totalDeps;

  public BuildDeps(ImmutableSortedSet<BuildRule> deps) {
    this(null, deps);
  }

  public BuildDeps(
      @Nullable BuildDeps previousRuleBuildDeps, ImmutableSortedSet<BuildRule> additionalDeps) {
    this.previousRuleBuildDeps = previousRuleBuildDeps;
    this.additionalDeps = additionalDeps;

    totalDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(previousRuleBuildDeps != null ? previousRuleBuildDeps : Collections.emptySet())
            .addAll(additionalDeps)
            .build();
  }

  @Override
  public Comparator<? super BuildRule> comparator() {
    return totalDeps.comparator();
  }

  @Override
  public SortedSet<BuildRule> subSet(BuildRule fromElement, BuildRule toElement) {
    return totalDeps.subSet(fromElement, toElement);
  }

  @Override
  public SortedSet<BuildRule> headSet(BuildRule toElement) {
    return totalDeps.headSet(toElement);
  }

  @Override
  public SortedSet<BuildRule> tailSet(BuildRule fromElement) {
    return totalDeps.tailSet(fromElement);
  }

  @Override
  public BuildRule first() {
    return totalDeps.first();
  }

  @Override
  public BuildRule last() {
    return totalDeps.last();
  }

  @Override
  public int size() {
    return totalDeps.size();
  }

  @Override
  public boolean isEmpty() {
    return totalDeps.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return totalDeps.contains(o);
  }

  @Override
  public Iterator<BuildRule> iterator() {
    return totalDeps.iterator();
  }
}
