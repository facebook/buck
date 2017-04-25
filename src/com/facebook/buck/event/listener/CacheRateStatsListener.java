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

package com.facebook.buck.event.listener;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.RateLimiter;

/** Posts rate-limited cache rate update events for the WebSocket to consume. */
public class CacheRateStatsListener implements BuckEventListener {
  private final CacheRateStatsKeeper cacheRateStatsKeeper;
  private final RateLimiter rateLimiter;
  private final BuckEventBus buckEventBus;

  public CacheRateStatsListener(BuckEventBus buckEventBus) {
    this.buckEventBus = buckEventBus;
    this.cacheRateStatsKeeper = new CacheRateStatsKeeper();
    this.rateLimiter = RateLimiter.create(1.0);
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    cacheRateStatsKeeper.ruleCountCalculated(calculated);
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    cacheRateStatsKeeper.ruleCountUpdated(updated);
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    cacheRateStatsKeeper.buildRuleFinished(finished);
    postRateLimitedCacheStatsUpdate();
  }

  @Subscribe
  public void buildFinished(@SuppressWarnings("unused") BuildEvent.Finished finished) {
    postCacheStatsUpdate();
  }

  private void postRateLimitedCacheStatsUpdate() {
    if (!rateLimiter.tryAcquire()) {
      return;
    }
    postCacheStatsUpdate();
  }

  private void postCacheStatsUpdate() {
    buckEventBus.post(cacheRateStatsKeeper.getStats());
  }

  @Override
  public void outputTrace(BuildId buildId) {
    // No-op.
  }
}
