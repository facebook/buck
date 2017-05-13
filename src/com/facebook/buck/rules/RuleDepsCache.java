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

package com.facebook.buck.rules;

import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A cache of rule deps. */
public class RuleDepsCache {
  private final Map<BuildRule, ImmutableSortedSet<BuildRule>> cache;
  private BuildRuleResolver resolver;

  public RuleDepsCache(BuildRuleResolver resolver) {
    this.resolver = resolver;
    this.cache = new ConcurrentHashMap<>();
  }

  public ImmutableSortedSet<BuildRule> get(final BuildRule rule) {
    return cache.computeIfAbsent(rule, this::computeDeps);
  }

  private ImmutableSortedSet<BuildRule> computeDeps(final BuildRule rule) {
    if (!(rule instanceof HasRuntimeDeps)) {
      return rule.getBuildDeps();
    }
    return RichStream.from(rule.getBuildDeps())
        .concat(resolver.getAllRules(((HasRuntimeDeps) rule).getRuntimeDeps()::iterator).stream())
        .collect(MoreCollectors.toImmutableSortedSet());
  }
}
