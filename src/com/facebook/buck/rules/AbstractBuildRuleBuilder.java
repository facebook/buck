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
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@Beta
public abstract class AbstractBuildRuleBuilder implements BuildRuleBuilder {

  protected AbstractBuildRuleBuilder() {}

  protected BuildTarget buildTarget;
  protected Set<String> deps = Sets.newHashSet();
  protected Set<BuildTargetPattern> visibilityPatterns = Sets.newHashSet();

  @Override
  public abstract BuildRule build(Map<String, BuildRule> buildRuleIndex);

  public AbstractBuildRuleBuilder setBuildTarget(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
    return this;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public AbstractBuildRuleBuilder addDep(String dep) {
    deps.add(dep);
    return this;
  }

  /** @return a view of the deps of this rule */
  @Override
  public Set<String> getDeps() {
    return Collections.unmodifiableSet(deps);
  }

  protected ImmutableSortedSet<BuildRule> getDepsAsBuildRules(
      final Map<String, BuildRule> buildRuleIndex) {
    return getBuildTargetsAsBuildRules(buildRuleIndex, getDeps(), false /* allowNonExistentRule */);
  }

  @VisibleForTesting
  ImmutableSortedSet<BuildRule> getBuildTargetsAsBuildRules(
      final Map<String, BuildRule> buildRuleIndex,
      Iterable<String> buildTargets,
      boolean allowNonExistentRule) {
    BuildTarget invokingBuildTarget = Preconditions.checkNotNull(getBuildTarget());

    ImmutableSortedSet.Builder<BuildRule> buildRules = ImmutableSortedSet.naturalOrder();

    for (String target : buildTargets) {
      BuildRule buildRule = buildRuleIndex.get(target);
      if (buildRule != null) {
        buildRules.add(buildRule);
      } else if (!allowNonExistentRule) {
        throw new HumanReadableException("No rule for %s found when processing %s",
            target, invokingBuildTarget.getFullyQualifiedName());
      }
    }

    return buildRules.build();
  }

  public AbstractBuildRuleBuilder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
    visibilityPatterns.add(visibilityPattern);
    return this;
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return ImmutableSet.copyOf(visibilityPatterns);
  }

  protected BuildRuleParams createBuildRuleParams(Map<String, BuildRule> buildRuleIndex) {
    return new BuildRuleParams(getBuildTarget(),
        getDepsAsBuildRules(buildRuleIndex),
        getVisibilityPatterns());
  }
}
