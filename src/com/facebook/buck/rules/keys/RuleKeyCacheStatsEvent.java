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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.cache.CacheStats;

public class RuleKeyCacheStatsEvent extends AbstractBuckEvent {

  private final CacheStats stats;

  private RuleKeyCacheStatsEvent(EventKey eventKey, CacheStats stats) {
    super(eventKey);
    this.stats = stats;
  }

  public static RuleKeyCacheStatsEvent create(CacheStats stats) {
    return new RuleKeyCacheStatsEvent(EventKey.unique(), stats);
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }

  @Override
  protected String getValueString() {
    return "";
  }

  public CacheStats getStats() {
    return stats;
  }
}
