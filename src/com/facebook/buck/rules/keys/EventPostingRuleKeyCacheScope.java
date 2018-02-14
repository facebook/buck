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
import com.facebook.buck.event.CacheStatsEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.util.cache.CacheStats;

/** A {@link RuleKeyCacheScope} which logs stats on close. */
public class EventPostingRuleKeyCacheScope<V> implements RuleKeyCacheScope<V> {

  private final BuckEventBus buckEventBus;
  private final TrackedRuleKeyCache<V> cache;

  public EventPostingRuleKeyCacheScope(BuckEventBus buckEventBus, TrackedRuleKeyCache<V> cache) {
    this.buckEventBus = buckEventBus;
    this.cache = cache;

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(buckEventBus, PerfEventId.of("rule_key_cache_setup"))) {

      // Run additional setup.
      setup(scope);
    }
  }

  /** Additional setup. To be implemented by sub-classes. */
  protected void setup(@SuppressWarnings("unused") SimplePerfEvent.Scope scope) {}

  /** Additional cleanup. To be implemented by sub-classes. */
  protected void cleanup(@SuppressWarnings("unused") SimplePerfEvent.Scope scope) {}

  @Override
  public final TrackedRuleKeyCache<V> getCache() {
    return cache;
  }

  @Override
  public final void close() {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(buckEventBus, PerfEventId.of("rule_key_cache_cleanup"))) {

      // Log stats.
      CacheStats stats = cache.getStats();
      buckEventBus.post(new CacheStatsEvent("rule_key_cache", stats));
      scope.update("hitRate", stats.hitRate());
      scope.update("hits", stats.getHitCount());
      scope.update("misses", stats.getMissCount());
      scope.update("requests", stats.getRequestCount());
      scope.update("load_time_ms", stats.getTotalLoadTime());

      // Run additional cleanup.
      cleanup(scope);
    }
  }
}
