/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;

/** A cache of rule deps. */
public class DefaultRuleDepsCache implements RuleDepsCache {
  private final Map<BuildRule, SortedSet<BuildRule>> allDepsCache;
  private final Map<BuildRule, SortedSet<BuildRule>> runtimeDepsCache;
  private final BuildRuleResolver resolver;
  private final SourcePathRuleFinder ruleFinder;

  public DefaultRuleDepsCache(BuildRuleResolver resolver) {
    this.resolver = resolver;
    this.ruleFinder = new SourcePathRuleFinder(resolver);
    this.allDepsCache = new ConcurrentHashMap<>();
    this.runtimeDepsCache = new ConcurrentHashMap<>();
  }

  @Override
  public SortedSet<BuildRule> get(BuildRule rule) {
    return allDepsCache.computeIfAbsent(rule, this::computeDeps);
  }

  private SortedSet<BuildRule> computeDeps(BuildRule rule) {
    return SortedSets.union(rule.getBuildDeps(), getRuntimeDeps(rule));
  }

  @Override
  public SortedSet<BuildRule> getRuntimeDeps(BuildRule rule) {
    return runtimeDepsCache.computeIfAbsent(rule, this::computeRuntimeDeps);
  }

  private SortedSet<BuildRule> computeRuntimeDeps(BuildRule rule) {
    if (!(rule instanceof HasRuntimeDeps)) {
      return ImmutableSortedSet.of();
    }

    return resolver.getAllRules(
        RichStream.from(((HasRuntimeDeps) rule).getRuntimeDeps(ruleFinder)).toOnceIterable());
  }
}
