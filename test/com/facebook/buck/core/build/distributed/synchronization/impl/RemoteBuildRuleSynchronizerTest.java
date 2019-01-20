/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.distributed.synchronization.impl;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.testutil.FakeExecutor;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteBuildRuleSynchronizerTest {

  private final long START_TIMESTAMP_MILLIS = 123456789;
  private final long FIRST_BACKOFF_MILLIS = 2000;
  private final long MAX_TOTAL_BACKOFF_MILLIS = 9000;
  private final CacheResult CACHE_HIT = CacheResult.hit("", ArtifactCacheMode.thrift_over_http);
  private final CacheResult CACHE_MISS = CacheResult.miss();
  private final FakeBuildRule FAKE_RULE =
      new FakeBuildRule(BuildTargetFactory.newInstance("//build:rule"), ImmutableSortedSet.of());

  private SettableFakeClock fakeClock;
  private FakeExecutor fakeExecutor;
  private RemoteBuildRuleSynchronizer synchronizer;

  @Before
  public void setUp() {
    fakeClock = new SettableFakeClock(START_TIMESTAMP_MILLIS, 0);
    fakeExecutor = new FakeExecutor();
    synchronizer =
        new RemoteBuildRuleSynchronizer(
            fakeClock, fakeExecutor, FIRST_BACKOFF_MILLIS, MAX_TOTAL_BACKOFF_MILLIS);
  }

  @After
  public void tearDown() {
    synchronizer.close();
    fakeExecutor.shutdown();
  }

  @Test
  public void testSuccesfulWaitForRuleWithBackOffs() {
    AtomicInteger cacheChecks = new AtomicInteger(0);
    ListenableFuture<CacheResult> resultFuture =
        synchronizer.waitForBuildRuleToAppearInCache(
            FAKE_RULE,
            () -> {
              CacheResult cacheResult = cacheChecks.incrementAndGet() > 2 ? CACHE_HIT : CACHE_MISS;
              return Futures.immediateFuture(cacheResult);
            });

    // Unlock normal first check (miss, puts a TimestampedFuture in backoffs list)
    synchronizer.signalCompletionOfBuildRule(FAKE_RULE.getFullyQualifiedName());
    // Trigger "ready" backed off rules - no ready, no cacheChecks done
    fakeExecutor.drain();
    // Set clock so that the TimestampedFuture is "ready"
    fakeClock.setCurrentTimeMillis(fakeClock.currentTimeMillis() + FIRST_BACKOFF_MILLIS + 1);
    // Trigger "ready" backed off rules - TimestampedFuture is "ready", cacheCheck done
    // (miss, puts a TimestampedFuture in backoffs list)
    fakeExecutor.drain();
    // Set clock so that the TimestampedFuture is "ready" -> cacheck (hit)
    fakeClock.setCurrentTimeMillis(fakeClock.currentTimeMillis() + MAX_TOTAL_BACKOFF_MILLIS);
    fakeExecutor.drain();

    CacheResult result;
    try {
      result = resultFuture.get(100, TimeUnit.MILLISECONDS);
      Assert.assertEquals(CacheResultType.HIT, result.getType());
      Assert.assertEquals(3, cacheChecks.get());
    } catch (Exception e) {
      Assert.fail(
          String.format("Getting cache check result failed. Error message: %s", e.getMessage()));
    }
  }

  @Test
  public void testUnsuccessfulWaitForRuleWithBackOffs() {
    AtomicInteger cacheChecks = new AtomicInteger(0);
    ListenableFuture<CacheResult> resultFuture =
        synchronizer.waitForBuildRuleToAppearInCache(
            FAKE_RULE,
            () -> {
              cacheChecks.incrementAndGet();
              return Futures.immediateFuture(CACHE_MISS);
            });

    // Unlock first check, try unlocking first backoff, do 2 backed off cache checks - misses
    synchronizer.signalCompletionOfBuildRule(FAKE_RULE.getFullyQualifiedName());
    fakeExecutor.drain();
    fakeClock.setCurrentTimeMillis(fakeClock.currentTimeMillis() + FIRST_BACKOFF_MILLIS + 1);
    fakeExecutor.drain();
    fakeClock.setCurrentTimeMillis(fakeClock.currentTimeMillis() + MAX_TOTAL_BACKOFF_MILLIS);
    fakeExecutor.drain();

    CacheResult result;
    try {
      result = resultFuture.get(100, TimeUnit.MILLISECONDS);
      Assert.assertEquals(CacheResultType.MISS, result.getType());
      Assert.assertEquals(3, cacheChecks.get());
    } catch (Exception e) {
      Assert.fail(
          String.format("Getting cache check result failed. Error message: %s", e.getMessage()));
    }
  }

  @Test
  public void testUnlockedRuleHasNoBackOffs() {
    synchronizer.signalUnlockedBuildRule(FAKE_RULE.getFullyQualifiedName());
    checkInstantMiss();
  }

  @Test
  public void testCloseCancelsBackoffs() {
    synchronizer.signalCompletionOfBuildRule(FAKE_RULE.getFullyQualifiedName());
    synchronizer.close();
    checkInstantMiss();
  }

  @Test
  public void testBuildFailureCancelsBackoffs() {
    synchronizer.signalCompletionOfRemoteBuild(false);
    checkInstantMiss();
  }

  @Test
  public void testSuccesfulBuildCompletionSetsTimeLimitForBackoffs() {
    AtomicInteger cacheChecks = new AtomicInteger(0);
    ListenableFuture<CacheResult> resultFuture =
        synchronizer.waitForBuildRuleToAppearInCache(
            FAKE_RULE,
            () -> {
              cacheChecks.incrementAndGet();
              return Futures.immediateFuture(CACHE_MISS);
            });

    // This sets the timestamp that will be used as completion for the rule now + unlocks first
    // cacheCheck (miss)
    synchronizer.signalCompletionOfRemoteBuild(true);
    // Set clock so that our TimestampedFuture for backoff is "ready"
    fakeClock.setCurrentTimeMillis(fakeClock.currentTimeMillis() + FIRST_BACKOFF_MILLIS + 1);
    // Signal completion which sets a timestamp, but will not be used as rule completion timestamp
    // anymore - the build completion occurred earlier.
    synchronizer.signalCompletionOfBuildRule(FAKE_RULE.getFullyQualifiedName());
    // Trigger "ready" backed off rules - TimestampedFuture is "ready", cacheCheck done (miss)
    fakeExecutor.drain();
    // Set clock so that we are past the max backoff (calculated vs the "build completed" timestamp)
    fakeClock.setCurrentTimeMillis(
        fakeClock.currentTimeMillis() + MAX_TOTAL_BACKOFF_MILLIS - FIRST_BACKOFF_MILLIS);
    // Last check (miss)
    fakeExecutor.drain();

    CacheResult result;
    try {
      result = resultFuture.get(100, TimeUnit.MILLISECONDS);
      Assert.assertEquals(CacheResultType.MISS, result.getType());
      Assert.assertEquals(3, cacheChecks.get());
    } catch (Exception e) {
      Assert.fail(
          String.format("Getting cache check result failed. Error message: %s", e.getMessage()));
    }
  }

  @Test
  public void testBackOffsDisabled() {
    synchronizer.close(); // Close the standard synchronizer.
    // Synchronizer with disabled backoffs.
    synchronizer = new RemoteBuildRuleSynchronizer(fakeClock, fakeExecutor, 0, 0);
    synchronizer.signalCompletionOfBuildRule(FAKE_RULE.getFullyQualifiedName());
    checkInstantMiss();
  }

  private void checkInstantMiss() {
    AtomicInteger cacheChecks = new AtomicInteger(0);
    ListenableFuture<CacheResult> resultFuture =
        synchronizer.waitForBuildRuleToAppearInCache(
            FAKE_RULE,
            () -> {
              cacheChecks.incrementAndGet();
              return Futures.immediateFuture(CACHE_MISS);
            });
    CacheResult result;
    try {
      result = resultFuture.get(100, TimeUnit.MILLISECONDS);
      Assert.assertEquals(CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, cacheChecks.get());
    } catch (Exception e) {
      Assert.fail(
          String.format("Getting cache check result failed. Error message: %s", e.getMessage()));
    }
  }
}
