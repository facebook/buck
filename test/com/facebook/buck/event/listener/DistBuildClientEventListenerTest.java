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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.RuleKeyCacheResult;
import com.facebook.buck.artifact_cache.RuleKeyCacheResultEvent;
import com.facebook.buck.distributed.DistBuildClientCacheResult;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.rules.keys.RuleKeyType;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

public class DistBuildClientEventListenerTest {
  private static final String BUILD_TARGET_ZERO = "buildTargetZero";
  private static final String RULE_KEY_ZERO = "ruleKeyZero";
  private static final int REQUEST_TIMESTAMP_MILLIS_ZERO = 100;

  private static final String BUILD_TARGET_ONE = "buildTargetOne";
  private static final String RULE_KEY_ONE = "ruleKeyOne";
  private static final int REQUEST_TIMESTAMP_MILLIS_ONE = 101;
  private static final String CONTENT_HASH_KEY_ONE = "ruleKeyOneContentHashKey";
  private static final int LAST_STORED_TS_MILLIS_ONE = 1;

  private static final String BUILD_TARGET_TWO = "buildTargetTwo";
  private static final String RULE_KEY_TWO = "ruleKeyTwo";
  private static final int REQUEST_TIMESTAMP_MILLIS_TWO = 102;

  private static final String BUILD_TARGET_THREE = "buildTargetThree";
  private static final String RULE_KEY_THREE = "ruleKeyThree";
  private static final int REQUEST_TIMESTAMP_MILLIS_THREE = 103;

  private static final String BUILD_TARGET_FOUR = "buildTargetFour";
  private static final String RULE_KEY_FOUR = "ruleKeyFour";
  private static final int REQUEST_TIMESTAMP_MILLIS_FOUR = 104;

  private static final boolean CACHE_HIT_EXPECTED = true;
  private static final boolean CACHE_MISS_ACCEPTABLE = false;

  @Test
  public void testDefaultRuleKeyCacheMissesCorrectlyTurnedIntoEvent() throws IOException {
    DistBuildClientEventListener listener = new DistBuildClientEventListener();

    List<RuleKeyLogEntry> serverSideLogs = Lists.newArrayList();

    // Cache fetches made before remote building is complete should be skipped, as it is
    // perfectly normal for these to be cahce misses
    RuleKeyCacheResult ruleKeyCacheResultZero =
        RuleKeyCacheResult.builder()
            .setBuildTarget(BUILD_TARGET_ZERO)
            .setRuleKey(RULE_KEY_ZERO)
            .setRequestTimestampMillis(REQUEST_TIMESTAMP_MILLIS_ZERO)
            .setRuleKeyType(RuleKeyType.DEFAULT)
            .setCacheResult(CacheResultType.MISS)
            .build();
    listener.ruleKeyCacheResultEventHandler(
        new RuleKeyCacheResultEvent(ruleKeyCacheResultZero, CACHE_MISS_ACCEPTABLE));

    // Publish a default rule key miss, including second level content hash key.
    // (i.e. there was a hit for the rule key, but the content key was a miss).
    // Save server-side entry saying both this key and the content were stored.
    RuleKeyCacheResult ruleKeyCacheResultOne =
        RuleKeyCacheResult.builder()
            .setBuildTarget(BUILD_TARGET_ONE)
            .setRuleKey(RULE_KEY_ONE)
            .setRequestTimestampMillis(REQUEST_TIMESTAMP_MILLIS_ONE)
            .setRuleKeyType(RuleKeyType.DEFAULT)
            .setCacheResult(CacheResultType.MISS)
            .setTwoLevelContentHashKey(CONTENT_HASH_KEY_ONE)
            .build();

    listener.ruleKeyCacheResultEventHandler(
        new RuleKeyCacheResultEvent(ruleKeyCacheResultOne, CACHE_HIT_EXPECTED));

    RuleKeyLogEntry ruleKeyOneServerLog =
        createRuleKeyLogEntry(RULE_KEY_ONE, true, LAST_STORED_TS_MILLIS_ONE);
    serverSideLogs.add(ruleKeyOneServerLog);

    RuleKeyLogEntry contentHashkeyOneServerLog =
        createRuleKeyLogEntry(CONTENT_HASH_KEY_ONE, true, LAST_STORED_TS_MILLIS_ONE);
    serverSideLogs.add(contentHashkeyOneServerLog);

    // Publish a default rule key hit, including second level content hash key.
    // => This should be ignored, as we only care about default rule key misses.
    RuleKeyCacheResult ruleKeyCacheResultTwo =
        RuleKeyCacheResult.builder()
            .setBuildTarget(BUILD_TARGET_TWO)
            .setRuleKey(RULE_KEY_TWO)
            .setRequestTimestampMillis(REQUEST_TIMESTAMP_MILLIS_TWO)
            .setRuleKeyType(RuleKeyType.DEFAULT)
            .setCacheResult(CacheResultType.HIT)
            .build();

    listener.ruleKeyCacheResultEventHandler(
        new RuleKeyCacheResultEvent(ruleKeyCacheResultTwo, CACHE_HIT_EXPECTED));

    // Publish an input rule key miss.
    // => This should be ignored, as we only care about default rule key misses.
    RuleKeyCacheResult ruleKeyCacheResultThree =
        RuleKeyCacheResult.builder()
            .setBuildTarget(BUILD_TARGET_THREE)
            .setRuleKey(RULE_KEY_THREE)
            .setRequestTimestampMillis(REQUEST_TIMESTAMP_MILLIS_THREE)
            .setRuleKeyType(RuleKeyType.INPUT)
            .setCacheResult(CacheResultType.MISS)
            .build();

    listener.ruleKeyCacheResultEventHandler(
        new RuleKeyCacheResultEvent(ruleKeyCacheResultThree, CACHE_HIT_EXPECTED));

    // Publish a default rule key miss, without second level content key.
    // (i.e. the rule key itself was a miss, so we never got to querying the content hash key).
    // Save server-side entry saying the key was not stored.
    RuleKeyCacheResult ruleKeyCacheResultFour =
        RuleKeyCacheResult.builder()
            .setBuildTarget(BUILD_TARGET_FOUR)
            .setRuleKey(RULE_KEY_FOUR)
            .setRequestTimestampMillis(REQUEST_TIMESTAMP_MILLIS_FOUR)
            .setRuleKeyType(RuleKeyType.DEFAULT)
            .setCacheResult(CacheResultType.MISS)
            .build();

    listener.ruleKeyCacheResultEventHandler(
        new RuleKeyCacheResultEvent(ruleKeyCacheResultFour, CACHE_HIT_EXPECTED));
    RuleKeyLogEntry ruleKeyFourServerLog = createRuleKeyLogEntry(RULE_KEY_FOUR, false, -1);
    serverSideLogs.add(ruleKeyFourServerLog);

    // Generate the final event and assert it has the expected distributed client cache results.
    List<DistBuildClientCacheResult> cacheResults =
        listener.createDistBuildClientCacheResultsEvent(serverSideLogs).getCacheResults();

    assertEquals(2, cacheResults.size());

    DistBuildClientCacheResult expectedResultOne =
        DistBuildClientCacheResult.builder()
            .setClientSideCacheResult(ruleKeyCacheResultOne)
            .setRuleKeyServerLog(ruleKeyOneServerLog)
            .setTwoLevelContentHashKeyServerLog(contentHashkeyOneServerLog)
            .build();
    assertContains(cacheResults, expectedResultOne);

    DistBuildClientCacheResult expectedResultTwo =
        DistBuildClientCacheResult.builder()
            .setClientSideCacheResult(ruleKeyCacheResultFour)
            .setRuleKeyServerLog(ruleKeyFourServerLog)
            .build();
    assertContains(cacheResults, expectedResultTwo);

    listener.close();
  }

  private static void assertContains(
      Collection<DistBuildClientCacheResult> results, DistBuildClientCacheResult value) {
    assertEquals(1, results.stream().filter(r -> r.equals(value)).count());
  }

  private static RuleKeyLogEntry createRuleKeyLogEntry(
      String ruleKey, boolean wasStored, long lastStoredTsMillis) {
    RuleKeyLogEntry ruleKeyLogEntry = new RuleKeyLogEntry();
    ruleKeyLogEntry.setRuleKey(ruleKey);
    ruleKeyLogEntry.setWasStored(wasStored);
    ruleKeyLogEntry.setLastStoredTimestampMillis(lastStoredTsMillis);
    return ruleKeyLogEntry;
  }
}
