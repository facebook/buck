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
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

@Beta
public abstract class AbstractBuildRuleBuilder<T extends BuildRule> implements BuildRuleBuilder<T> {

  protected BuildTarget buildTarget;
  protected Set<BuildTarget> deps = Sets.newHashSet();
  protected Set<BuildTargetPattern> visibilityPatterns = Sets.newHashSet();

  private final Function<Path, Path> pathRelativizer;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;

  protected AbstractBuildRuleBuilder(AbstractBuildRuleBuilderParams params) {
    this.pathRelativizer = params.getPathAbsolutifier();
    this.ruleKeyBuilderFactory = params.getRuleKeyBuilderFactory();
  }

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

  protected ImmutableSortedSet<BuildRule> getDepsAsBuildRules(
      final BuildRuleResolver ruleResolver) {
    return getBuildTargetsAsBuildRules(ruleResolver, getDeps(), false /* allowNonExistentRule */);
  }

  protected ImmutableSortedSet<BuildRule> getBuildTargetsAsBuildRules(
      BuildRuleResolver ruleResolver, Iterable<BuildTarget> buildTargets) {
    return getBuildTargetsAsBuildRules(ruleResolver,
        buildTargets,
        /* allowNonExistentRule */ false);
  }

  @VisibleForTesting
  protected ImmutableSortedSet<BuildRule> getBuildTargetsAsBuildRules(
      BuildRuleResolver ruleResolver,
      Iterable<BuildTarget> buildTargets,
      boolean allowNonExistentRule) {
    BuildTarget invokingBuildTarget = Preconditions.checkNotNull(getBuildTarget());

    ImmutableSortedSet.Builder<BuildRule> buildRules = ImmutableSortedSet.naturalOrder();

    for (BuildTarget target : buildTargets) {
      BuildRule buildRule = ruleResolver.get(target);
      if (buildRule != null) {
        buildRules.add(buildRule);
      } else if (!allowNonExistentRule) {
        throw new HumanReadableException("No rule for %s found when processing %s",
            target, invokingBuildTarget.getFullyQualifiedName());
      }
    }

    return buildRules.build();
  }

  public AbstractBuildRuleBuilder<T> addVisibilityPattern(BuildTargetPattern visibilityPattern) {
    visibilityPatterns.add(visibilityPattern);
    return this;
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return ImmutableSet.copyOf(visibilityPatterns);
  }

  protected BuildRuleParams createBuildRuleParams(BuildRuleResolver ruleResolver) {
    return new BuildRuleParams(getBuildTarget(),
        getDepsAsBuildRules(ruleResolver),
        getVisibilityPatterns(),
        pathRelativizer,
        ruleKeyBuilderFactory);
  }
}
