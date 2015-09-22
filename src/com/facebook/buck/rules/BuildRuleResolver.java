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
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Set;

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
    this.buildRuleIndex = Maps.newHashMap(buildRuleIndex);
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
   * Returns the {@link BuildRule} with the {@code buildTarget}.
   */
  public BuildRule getRule(BuildTarget buildTarget) {
    BuildRule rule = buildRuleIndex.get(buildTarget);
    if (rule == null) {
      throw new HumanReadableException("Rule for target '%s' could not be resolved.", buildTarget);
    }
    return rule;
  }

  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    return Optional.fromNullable(buildRuleIndex.get(buildTarget));
  }

  @SuppressWarnings("unchecked")
  public <T extends BuildRule> Optional<T> getRuleOptionalWithType(
      BuildTarget buildTarget,
      Class<T> cls) {
    BuildRule rule = buildRuleIndex.get(buildTarget);
    if (rule != null) {
      if (cls.isInstance(rule)) {
        return Optional.of((T) rule);
      } else {
        throw new HumanReadableException(
            "Rule for target '%s' is present but not of expected type %s (got %s)",
            buildTarget,
            cls,
            rule.getClass());
      }
    }
    return Optional.absent();
  }

  public Function<BuildTarget, BuildRule> getRuleFunction() {
    return new Function<BuildTarget, BuildRule>() {
      @Override
      public BuildRule apply(BuildTarget input) {
        return getRule(input);
      }
    };
  }

  public ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets) {
    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();
    for (BuildTarget target : targets) {
      rules.add(getRule(target));
    }
    return rules.build();
  }

  /**
   * Adds to the index a mapping from {@code buildRule}'s target to itself and returns
   * {@code buildRule}.
   */
  @VisibleForTesting
  public <T extends BuildRule> T addToIndex(T buildRule) {
    Preconditions.checkArgument(!buildRule.getBuildTarget().getCell().isPresent());

    BuildRule oldValue = buildRuleIndex.put(buildRule.getBuildTarget(), buildRule);
    // Yuck! This is here to make it possible for a rule to depend on a flavor of itself but it
    // would be much much better if we just got rid of the BuildRuleResolver entirely.
    if (oldValue != null && oldValue != buildRule) {
      throw new IllegalStateException("A build rule for this target has already been created: " +
          oldValue.getBuildTarget());
    }
    return buildRule;
  }

  /**
   * Adds an iterable of build rules to the index.
   */
  public <T extends BuildRule, C extends Iterable<T>> C addAllToIndex(C buildRules) {
    for (T buildRule : buildRules) {
      addToIndex(buildRule);
    }
    return buildRules;
  }

}
