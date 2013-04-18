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
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public abstract class AbstractBuildRuleBuilder implements BuildRuleBuilder {

  AbstractBuildRuleBuilder() {}

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
    return ImmutableSortedSet.copyOf(Iterables.transform(
        getDeps(), new Function<String, BuildRule>() {
      @Override
      public BuildRule apply(String dep) {
        BuildRule buildRule = buildRuleIndex.get(dep);
        Preconditions.checkNotNull(buildRule, "No rule for %s", dep);
        return buildRule;
      }
    }));
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
