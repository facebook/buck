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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.google.common.cache.CacheStats;

/** A {@link RuleKeyCacheScope} which logs stats on close. */
public class EventPostingRuleKeyCacheScope<V> implements RuleKeyCacheScope<V> {

  private final BuckEventBus buckEventBus;
  private final RuleKeyCache<V> cache;

  private final CacheStats startStats;

  public EventPostingRuleKeyCacheScope(BuckEventBus buckEventBus, RuleKeyCache<V> cache) {
    this.buckEventBus = buckEventBus;
    this.cache = cache;

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(buckEventBus, PerfEventId.of("rule_key_cache_setup"))) {

      // Record the initial stats.
      startStats = cache.getStats();

      // Run additional setup.
      setup(scope);
    }
  }

  /** Additional setup. To be implemented by sub-classes. */
  protected void setup(@SuppressWarnings("unused") SimplePerfEvent.Scope scope) {}

  /** Additional cleanup. To be implemented by sub-classes. */
  protected void cleanup(@SuppressWarnings("unused") SimplePerfEvent.Scope scope) {}

  @Override
  public final RuleKeyCache<V> getCache() {
    return cache;
  }

  @Override
  public final void close() {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(buckEventBus, PerfEventId.of("rule_key_cache_cleanup"))) {

      // Log stats.
      CacheStats stats = cache.getStats().minus(startStats);
      buckEventBus.post(RuleKeyCacheStatsEvent.create(stats));
      scope.update("hitRate", stats.hitRate());
      scope.update("hits", stats.hitCount());
      scope.update("misses", stats.missCount());
      scope.update("requests", stats.requestCount());
      scope.update("load_time_ns", stats.totalLoadTime());

      // Run additional cleanup.
      cleanup(scope);
    }
  }
}
