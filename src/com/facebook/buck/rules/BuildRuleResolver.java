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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Provides a mechanism for mapping between a {@link BuildTarget} and the {@link BuildRule} it
 * represents. Once parsing is complete, instances of this class can be considered immutable.
 */
public class BuildRuleResolver {

  private final Map<BuildTarget, BuildRule> buildRuleIndex;

  public BuildRuleResolver() {
    this(Maps.<BuildTarget, BuildRule>newConcurrentMap());
  }

  @VisibleForTesting
  public BuildRuleResolver(Map<BuildTarget, BuildRule> buildRuleIndex) {
    this.buildRuleIndex = Preconditions.checkNotNull(buildRuleIndex);
  }

  @VisibleForTesting
  public BuildRuleResolver(Set<? extends BuildRule> startingSet) {
    this.buildRuleIndex = Maps.newConcurrentMap();
    for (BuildRule buildRule : startingSet) {
      this.buildRuleIndex.put(buildRule.getBuildTarget(), buildRule);
    }
  }

  /**
   * @return an unmodifiable view of the rules in the index
   */
  public Iterable<BuildRule> getBuildRules() {
    return Iterables.unmodifiableIterable(buildRuleIndex.values());
  }

  /**
   * Returns the {@link BuildRule} with the {@code fullyQualifiedName}.
   * @param fullyQualifiedName if {@code null}, this method will return {@code null}
   */
  @Nullable
  public BuildRule get(@Nullable BuildTarget fullyQualifiedName) {
    return fullyQualifiedName == null ? null : buildRuleIndex.get(fullyQualifiedName);
  }

  public <T extends BuildRule> T buildAndAddToIndex(BuildRuleBuilder<T> builder) {
    T buildRule = builder.build(this);
    BuildRule oldValue = buildRuleIndex.put(buildRule.getBuildTarget(), buildRule);
    if (oldValue != null) {
      throw new IllegalStateException("A build rule for this target has already been created: " +
          buildRule.getBuildTarget());
    }
    return buildRule;
  }
}
