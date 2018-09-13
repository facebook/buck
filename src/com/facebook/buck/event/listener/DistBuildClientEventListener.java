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
package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.RuleKeyCacheResult;
import com.facebook.buck.artifact_cache.RuleKeyCacheResultEvent;
import com.facebook.buck.distributed.DistBuildClientCacheResult;
import com.facebook.buck.distributed.DistBuildClientCacheResult.Builder;
import com.facebook.buck.distributed.DistBuildClientCacheResultEvent;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.rules.keys.RuleKeyType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class DistBuildClientEventListener implements BuckEventListener {
  private final Set<String> firstLevelRuleKeys = Sets.newHashSet(); // i.e. the real rule key.
  private final Set<String> secondLevelContentKeys = Sets.newHashSet();

  private final Map<String, DistBuildClientCacheResult.Builder> cacheResultsByKey =
      Maps.newHashMap();

  @Subscribe
  public synchronized void ruleKeyCacheResultEventHandler(
      RuleKeyCacheResultEvent cacheResultEvent) {
    RuleKeyCacheResult ruleKeyCacheResult = cacheResultEvent.getRuleKeyCacheResult();
    // Stampede clients should get 100% default rule key hits, so the abnormal requests
    // we care about logging are default rule key misses (and for fetches made after remote
    // building of rule is complete).
    if (!cacheResultEvent.isCacheHitExpected()
        || ruleKeyCacheResult.cacheResult() != CacheResultType.MISS
        || ruleKeyCacheResult.ruleKeyType() != RuleKeyType.DEFAULT) {
      return;
    }

    String ruleKey = ruleKeyCacheResult.ruleKey();
    DistBuildClientCacheResult.Builder cacheResultBuilder =
        DistBuildClientCacheResult.builder().setClientSideCacheResult(ruleKeyCacheResult);

    firstLevelRuleKeys.add(ruleKey);

    // Misses should always be at the default rule key level. A second-level miss means that header
    // and content keys are being flushed out at different times, so we should log these cases.
    Optional<String> secondLevelContentHashKey = ruleKeyCacheResult.twoLevelContentHashKey();
    boolean wasTwoLevelRequest = secondLevelContentHashKey.isPresent();
    if (wasTwoLevelRequest) {
      secondLevelContentKeys.add(secondLevelContentHashKey.get());
    }

    cacheResultsByKey.put(ruleKey, cacheResultBuilder);
    if (wasTwoLevelRequest) {
      cacheResultsByKey.put(secondLevelContentHashKey.get(), cacheResultBuilder);
    }
  }

  public synchronized DistBuildClientCacheResultEvent createDistBuildClientCacheResultsEvent(
      List<RuleKeyLogEntry> ruleKeyLogs) {
    for (RuleKeyLogEntry ruleKeyLog : ruleKeyLogs) {
      String ruleKey = ruleKeyLog.getRuleKey();
      DistBuildClientCacheResult.Builder cacheRequestBuilder =
          Preconditions.checkNotNull(cacheResultsByKey.get(ruleKey));

      if (firstLevelRuleKeys.contains(ruleKey)) {
        cacheRequestBuilder.setRuleKeyServerLog(ruleKeyLog);
      } else {
        Preconditions.checkArgument(secondLevelContentKeys.contains(ruleKey));
        cacheRequestBuilder.setTwoLevelContentHashKeyServerLog(ruleKeyLog);
      }
    }

    List<DistBuildClientCacheResult> cacheResults =
        new HashSet<>(
                cacheResultsByKey.values()) // deduplicate builders pointed to by multiple keys
            .stream()
            .map(Builder::build)
            .collect(Collectors.toList());

    return new DistBuildClientCacheResultEvent(cacheResults);
  }

  public synchronized Set<String> getDefaultCacheMissRequestKeys() {
    Set<String> result = Sets.newHashSet();
    result.addAll(firstLevelRuleKeys);
    result.addAll(secondLevelContentKeys);
    return result;
  }
}
