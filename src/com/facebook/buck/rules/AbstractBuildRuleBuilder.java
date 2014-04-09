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
import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Set;

@Beta
public abstract class AbstractBuildRuleBuilder<T extends BuildRule> implements BuildRuleBuilder<T> {

  protected BuildTarget buildTarget;
  protected Set<BuildTarget> deps = Sets.newHashSet();
  protected Set<BuildTargetPattern> visibilityPatterns = Sets.newHashSet();

  /**
   * This method must support being able to be invoked multiple times, as this builder will be
   * reused when buckd is being used.
   */
  @Override
  public abstract T build(BuildRuleResolver ruleResolver);

  public AbstractBuildRuleBuilder<T> setBuildTarget(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
    return this;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public AbstractBuildRuleBuilder<T> addDep(BuildTarget dep) {
    deps.add(dep);
    return this;
  }

  /** @return a view of the deps of this rule */
  @Override
  public Set<BuildTarget> getDeps() {
    return Collections.unmodifiableSet(deps);
  }

  public AbstractBuildRuleBuilder<T> addVisibilityPattern(BuildTargetPattern visibilityPattern) {
    visibilityPatterns.add(visibilityPattern);
    return this;
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return ImmutableSet.copyOf(visibilityPatterns);
  }
}
