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

package com.facebook.buck.event.listener.scuba;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.RuleKeyStoreLogEntry;
import com.facebook.buck.event.listener.scuba.ScubaBuildListenerCacheMiss.CacheMissType;
import com.facebook.buck.event.listener.scuba.ScubaBuildListenerCacheMiss.ClassifyResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class ScubaBuildListenerCacheMissTest {

  private static final long RANDOM_TS_SECONDS =
      Instant.parse("2017-10-13T13:14:17Z").getEpochSecond();

  private void testClassifyCacheMiss(
      ScubaBuildListenerCacheMiss.CacheMissType expected, RuleKeyStoreLogEntry... logEntries) {

    RuleKeyLogEntry ruleKeyLogEntry = new RuleKeyLogEntry();
    ruleKeyLogEntry.setWasStored(true);
    ruleKeyLogEntry.setStoreLogEntries(Arrays.asList(logEntries));
    ClassifyResult classifyResult =
        ScubaBuildListenerCacheMiss.classifyRuleKeyLogEntry(
            Optional.of(ruleKeyLogEntry), RANDOM_TS_SECONDS * 1000);
    assertEquals(expected, classifyResult.cacheMissType);
  }

  private static RuleKeyStoreLogEntry memcache() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 5;
    e.storeTTLSeconds = 0;
    return e;
  }

  private static RuleKeyStoreLogEntry memcacheWithStoreAndFetch() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS;
    e.storeTTLSeconds = 0;
    e.lastAttemptedStoreEpochSeconds = RANDOM_TS_SECONDS - 20;
    e.lastFetchEpochSeconds = RANDOM_TS_SECONDS - 30;
    return e;
  }

  private static RuleKeyStoreLogEntry inSla() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 5;
    e.storeTTLSeconds = 20;
    return e;
  }

  private static RuleKeyStoreLogEntry outSlaOther() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 50;
    e.storeTTLSeconds = 20;
    return e;
  }

  private static RuleKeyStoreLogEntry outSlaOtherStoredFetched() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 50;
    e.lastAttemptedStoreEpochSeconds = RANDOM_TS_SECONDS - 40;
    e.lastFetchEpochSeconds = RANDOM_TS_SECONDS - 30;
    e.storeTTLSeconds = 20;
    return e;
  }

  private static RuleKeyStoreLogEntry outSlaWouldHaveBeenInSlaIfRefreshedOnStore() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 3000;
    e.lastAttemptedStoreEpochSeconds = RANDOM_TS_SECONDS - 3;
    e.storeTTLSeconds = 20;
    return e;
  }

  private static RuleKeyStoreLogEntry outSlaWouldHaveBeenInSlaIfRefreshedOnFetch() {
    RuleKeyStoreLogEntry e = new RuleKeyStoreLogEntry();
    e.lastStoreEpochSeconds = RANDOM_TS_SECONDS - 3000;
    e.lastFetchEpochSeconds = RANDOM_TS_SECONDS - 5;
    e.storeTTLSeconds = 20;
    return e;
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_emptyPerStoreList() {
    testClassifyCacheMiss(ScubaBuildListenerCacheMiss.CacheMissType.EMPTY_PER_STORE_LIST);
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_memcache() {
    testClassifyCacheMiss(ScubaBuildListenerCacheMiss.CacheMissType.MEMCACHE, memcache());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.MEMCACHE,
        memcache(),
        memcacheWithStoreAndFetch());
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_inSla() {
    testClassifyCacheMiss(ScubaBuildListenerCacheMiss.CacheMissType.IN_SLA, inSla());
    testClassifyCacheMiss(ScubaBuildListenerCacheMiss.CacheMissType.IN_SLA, inSla(), memcache());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.IN_SLA,
        inSla(),
        memcache(),
        outSlaWouldHaveBeenInSlaIfRefreshedOnStore());
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_outSlaWouldHaveBeenInSlaIfRefreshedOnStore() {
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType
            .OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_STORE,
        outSlaWouldHaveBeenInSlaIfRefreshedOnStore());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType
            .OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_STORE,
        outSlaWouldHaveBeenInSlaIfRefreshedOnStore(),
        memcache());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType
            .OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_STORE,
        outSlaWouldHaveBeenInSlaIfRefreshedOnStore(),
        outSlaWouldHaveBeenInSlaIfRefreshedOnFetch());
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_outSlaWouldHaveBeenInSlaIfRefreshedOnFetch() {
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType
            .OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_FETCH,
        outSlaWouldHaveBeenInSlaIfRefreshedOnFetch());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType
            .OUT_SLA_WOULD_HAVE_BEEN_IN_SLA_IF_REFRESHED_ON_FETCH,
        outSlaWouldHaveBeenInSlaIfRefreshedOnFetch(),
        memcache());
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_outSlaOther() {
    testClassifyCacheMiss(ScubaBuildListenerCacheMiss.CacheMissType.OUT_SLA_OTHER, outSlaOther());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.OUT_SLA_OTHER, outSlaOtherStoredFetched());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.OUT_SLA_OTHER,
        outSlaOther(),
        outSlaOtherStoredFetched());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.OUT_SLA_OTHER, outSlaOther(), memcache());
  }

  /** Unittest */
  @Test
  public void classifyCacheMiss_incorrectRecord() {
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.INCORRECT_RECORD, new RuleKeyStoreLogEntry());
    testClassifyCacheMiss(
        ScubaBuildListenerCacheMiss.CacheMissType.INCORRECT_RECORD,
        new RuleKeyStoreLogEntry(),
        memcache());
  }

  /** Unittest */
  @Test
  public void classifyLogEntry_logsNotInitialized() {
    ClassifyResult classifyResult =
        ScubaBuildListenerCacheMiss.classifyRuleKeyLogEntry(Optional.empty(), RANDOM_TS_SECONDS);
    assertEquals(CacheMissType.LOGS_NOT_INITIALIZED, classifyResult.cacheMissType);
  }

  /** Unittest */
  @Test
  public void classifyLogEntry_neverCached() {
    RuleKeyLogEntry ruleKeyLogEntry = new RuleKeyLogEntry();
    ruleKeyLogEntry.wasStored = false;
    ClassifyResult classifyResult =
        ScubaBuildListenerCacheMiss.classifyRuleKeyLogEntry(
            Optional.of(ruleKeyLogEntry), RANDOM_TS_SECONDS);
    assertEquals(CacheMissType.NEVER_CACHED, classifyResult.cacheMissType);
  }

  /** Unittest */
  @Test
  public void classifyLogEntry_storeLogEntriesFieldNotSet() {
    RuleKeyLogEntry ruleKeyLogEntry = new RuleKeyLogEntry();
    ruleKeyLogEntry.wasStored = true;
    ClassifyResult classifyResult =
        ScubaBuildListenerCacheMiss.classifyRuleKeyLogEntry(
            Optional.of(ruleKeyLogEntry), RANDOM_TS_SECONDS);
    assertEquals(CacheMissType.STORE_LOG_ENTRIES_FIELD_NOT_SET, classifyResult.cacheMissType);
  }

  /** Unittest */
  @Test
  public void classifyLogEntry_emptyPerStoreList() {
    RuleKeyLogEntry ruleKeyLogEntry = new RuleKeyLogEntry();
    ruleKeyLogEntry.wasStored = true;
    ruleKeyLogEntry.storeLogEntries = Collections.emptyList();
    ClassifyResult classifyResult =
        ScubaBuildListenerCacheMiss.classifyRuleKeyLogEntry(
            Optional.of(ruleKeyLogEntry), RANDOM_TS_SECONDS);
    assertEquals(CacheMissType.EMPTY_PER_STORE_LIST, classifyResult.cacheMissType);
  }
}
