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

package com.facebook.buck.event.external.events;

/** Sent to update the WebSocket clients about cache misses and errors. */
public interface CacheRateStatsUpdateExternalEventInterface extends BuckEventExternalInterface {
  String EVENT_NAME = "CacheRateStatsUpdateEvent";

  /** @return number of cache misses. */
  int getCacheMissCount();

  /** @return number of cache errors. */
  int getCacheErrorCount();

  /** @return cache miss rate, in percent, as displayed on the buck console. */
  double getCacheMissRate();

  /** @return cache error rate, in percent, as displayed on the buck console. */
  double getCacheErrorRate();

  /**
   * @return number of cache hits. This excludes {@link
   *     com.facebook.buck.artifact_cache.CacheResultType#IGNORED} and {@link
   *     com.facebook.buck.artifact_cache.CacheResultType#LOCAL_KEY_UNCHANGED_HIT} result types.
   */
  int getCacheHitCount();

  /** @return number of rules that have been processed. */
  int getUpdatedRulesCount();
}
