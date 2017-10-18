/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.distributed.thrift.RuleKeyStoreLogEntry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Utility to classify cache misses from rule key logs. */
class ScubaBuildListenerCacheMiss {
  /** Main reasons why cache miss could happen */
  enum CacheMissType {
    IN_SLA,
    OUT_SLA_OTHER,
    OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_STORE,
    OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_FETCH,
    MEMCACHE,
    NEVER_CACHED,
    INCORRECT_RECORD,
  }

  /** A pair of cache miss type and a matching log entry */
  static class ClassifyResult {
    final CacheMissType cacheMissType;
    @Nullable final RuleKeyStoreLogEntry ruleKeyStoreLogEntry;

    ClassifyResult(
        CacheMissType cacheMissType, @Nullable RuleKeyStoreLogEntry ruleKeyStoreLogEntry) {
      this.cacheMissType = cacheMissType;
      this.ruleKeyStoreLogEntry = ruleKeyStoreLogEntry;
    }
  }

  static ClassifyResult classifyCacheMiss(
      List<RuleKeyStoreLogEntry> records, long currentTimeMillis) {
    // sanity checks
    for (RuleKeyStoreLogEntry record : records) {
      if (record.lastStoreEpochSeconds <= 0) {
        return new ClassifyResult(CacheMissType.INCORRECT_RECORD, record);
      }
      if (record.storeTTLSeconds < 0) {
        return new ClassifyResult(CacheMissType.INCORRECT_RECORD, record);
      }
      if (record.lastAttemptedStoreEpochSeconds < 0) {
        return new ClassifyResult(CacheMissType.INCORRECT_RECORD, record);
      }
      if (record.lastFetchEpochSeconds < 0) {
        return new ClassifyResult(CacheMissType.INCORRECT_RECORD, record);
      }
    }

    List<RuleKeyStoreLogEntry> withSlaLogEntries =
        records.stream().filter(r -> r.storeTTLSeconds != 0).collect(Collectors.toList());

    long currentTimeSeconds = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis);

    for (RuleKeyStoreLogEntry record : withSlaLogEntries) {
      if (record.lastStoreEpochSeconds + record.storeTTLSeconds > currentTimeSeconds) {
        // Worst possible case: artifact is in SLA and already expired
        return new ClassifyResult(CacheMissType.IN_SLA, record);
      }
    }

    for (RuleKeyStoreLogEntry record : withSlaLogEntries) {
      if (record.lastAttemptedStoreEpochSeconds == 0) {
        continue;
      }
      if (record.lastAttemptedStoreEpochSeconds + record.storeTTLSeconds > currentTimeSeconds) {
        // Refresh on store is more important than refresh on fetch
        return new ClassifyResult(
            CacheMissType.OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_STORE, record);
      }
    }

    for (RuleKeyStoreLogEntry record : withSlaLogEntries) {
      if (record.lastFetchEpochSeconds == 0) {
        continue;
      }
      if (record.lastFetchEpochSeconds + record.storeTTLSeconds > currentTimeSeconds) {
        return new ClassifyResult(
            CacheMissType.OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_FETCH, record);
      }
    }

    if (!withSlaLogEntries.isEmpty()) {
      // If there's any ZippyDB record then return it.
      return new ClassifyResult(CacheMissType.OUT_SLA_OTHER, withSlaLogEntries.get(0));
    }

    if (!records.isEmpty()) {
      // There were no ZippyDB stores, so it's memcache.
      return new ClassifyResult(CacheMissType.MEMCACHE, records.get(0));
    }

    // If record list is empty then rule key was not cached.
    return new ClassifyResult(CacheMissType.NEVER_CACHED, null);
  }
}
