/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * A {@link com.facebook.buck.rules.RuleKey} cache used by a {@link RuleKeyFactory}. As items are
 * add-only, this is intended to be used in a single build.
 *
 * @param <V> The rule key type.
 */
public class SingleBuildRuleKeyCache<V> {

  // Use key identity when caching.
  private final Cache<BuildRule, V> buildRuleCache = CacheBuilder.newBuilder().weakKeys().build();
  private final Cache<AddsToRuleKey, V> ruleKeyAppendableVCache =
      CacheBuilder.newBuilder().weakKeys().build();

  private <K> V getInternal(Cache<K, V> cache, K key, Function<K, V> create) {
    try {
      return cache.get(key, () -> create.apply(key));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  public V get(BuildRule rule, Function<BuildRule, V> create) {
    return getInternal(buildRuleCache, rule, create);
  }

  public V get(AddsToRuleKey appendable, Function<AddsToRuleKey, V> create) {
    return getInternal(ruleKeyAppendableVCache, appendable, create);
  }
}
