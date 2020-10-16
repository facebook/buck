/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.build.action.resolver.BuildEngineActionToBuildRuleResolver;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A cache of rule deps. */
public class DefaultRuleDepsCache implements RuleDepsCache {
  private final Map<BuildRule, Iterable<BuildRule>> buildDepsCache;
  private final Map<BuildRule, Iterable<BuildRule>> runtimeDepsCache;
  private final BuildRuleResolver resolver;
  private final BuildEngineActionToBuildRuleResolver actionToBuildRuleResolver;

  public DefaultRuleDepsCache(
      BuildRuleResolver resolver, BuildEngineActionToBuildRuleResolver actionToBuildRuleResolver) {
    this.resolver = resolver;
    this.actionToBuildRuleResolver = actionToBuildRuleResolver;
    this.buildDepsCache = new ConcurrentHashMap<>();
    this.runtimeDepsCache = new ConcurrentHashMap<>();
  }

  @Override
  public Iterable<BuildRule> get(BuildRule rule) {
    return Iterables.concat(getBuildDeps(rule), getRuntimeDeps(rule));
  }

  @Override
  public Iterable<BuildRule> getBuildDeps(BuildRule rule) {
    return buildDepsCache.computeIfAbsent(rule, BuildRule::getBuildDeps);
  }

  private Iterable<BuildRule> getRuntimeDeps(BuildRule rule) {
    return runtimeDepsCache.computeIfAbsent(rule, this::computeRuntimeDeps);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Iterable<BuildEngineAction> get(BuildEngineAction buildEngineAction) {
    return (Iterable<BuildEngineAction>)
        (Iterable<? extends BuildEngineAction>)
            get(actionToBuildRuleResolver.resolve(buildEngineAction));
  }

  private Iterable<BuildRule> computeRuntimeDeps(BuildRule rule) {
    if (!(rule instanceof HasRuntimeDeps)) {
      return ImmutableSet.of();
    }

    return ((HasRuntimeDeps) rule)
        .getRuntimeDeps(resolver)
        .map(resolver::getRule)
        .collect(ImmutableSet.toImmutableSet());
  }
}
