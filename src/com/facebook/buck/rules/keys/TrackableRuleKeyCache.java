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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.cache.CacheStatsTracker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * RuleKeyCache that can be tracked with {@code CacheStatsTracker}
 *
 * @param <V>
 */
public interface TrackableRuleKeyCache<V> {

  @Nullable
  V get(BuildRule rule, CacheStatsTracker statsTracker);

  V get(
      BuildRule rule,
      Function<? super BuildRule, RuleKeyResult<V>> create,
      CacheStatsTracker statsTracker);

  V get(
      AddsToRuleKey appendable,
      Function<? super AddsToRuleKey, RuleKeyResult<V>> create,
      CacheStatsTracker statsTracker);

  void invalidateInputs(Iterable<RuleKeyInput> inputs, CacheStatsTracker statsTracker);

  void invalidateAllExceptFilesystems(
      ImmutableSet<ProjectFilesystem> filesystems, CacheStatsTracker statsTracker);

  void invalidateFilesystem(ProjectFilesystem filesystem, CacheStatsTracker statsTracker);

  void invalidateAll(CacheStatsTracker statsTracker);

  ImmutableList<Map.Entry<BuildRule, V>> getCachedBuildRules();
}
