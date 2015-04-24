/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import javax.annotation.Nullable;

public class MultiArtifactCacheTest {
  private static final RuleKey dummyRuleKey =
      new RuleKey("76b1c1beae69428db2d1befb31cf743ac8ce90df");
  private static final File dummyFile = new File("dummy");

  class DummyArtifactCache extends NoopArtifactCache {

    @Nullable public RuleKey storeKey;

    public void reset() {
      storeKey = null;
    }

    @Override
    public CacheResult fetch(RuleKey ruleKey, File output) {
      return ruleKey.equals(storeKey) ? CacheResult.localKeyUnchangedHit() : CacheResult.miss();
    }

    @Override
    public void store(RuleKey ruleKey, File output) {
      storeKey = ruleKey;
    }

    @Override
    public boolean isStoreSupported() {
      return true;
    }

  }

  // An cache which always returns errors from fetching.
  class ErroringArtifactCache extends NoopArtifactCache {

    @Override
    public CacheResult fetch(RuleKey ruleKey, File output) {
      return CacheResult.error("cache", "error");
    }

  }

  @Test
  public void testCacheFetch() throws InterruptedException, IOException {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache = new MultiArtifactCache(ImmutableList.of(
        (ArtifactCache) dummyArtifactCache1,
        dummyArtifactCache2));

    assertEquals("Fetch should fail",
        CacheResult.Type.MISS,
        multiArtifactCache.fetch(dummyRuleKey, dummyFile).getType());

    dummyArtifactCache1.store(dummyRuleKey, dummyFile);
    assertEquals("Fetch should succeed after store",
        CacheResult.Type.LOCAL_KEY_UNCHANGED_HIT,
        multiArtifactCache.fetch(dummyRuleKey, dummyFile).getType());

    dummyArtifactCache1.reset();
    dummyArtifactCache2.reset();
    dummyArtifactCache2.store(dummyRuleKey, dummyFile);
    assertEquals("Fetch should succeed after store",
        CacheResult.Type.LOCAL_KEY_UNCHANGED_HIT,
        multiArtifactCache.fetch(dummyRuleKey, dummyFile).getType());

    multiArtifactCache.close();
  }

  @Test
  public void testCacheStore() throws InterruptedException, IOException {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache = new MultiArtifactCache(ImmutableList.<ArtifactCache>of(
        dummyArtifactCache1,
        dummyArtifactCache2));

    multiArtifactCache.store(dummyRuleKey, dummyFile);

    assertEquals("MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache1.storeKey,
        dummyRuleKey);
    assertEquals("MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache2.storeKey,
        dummyRuleKey);

    multiArtifactCache.close();
  }

  @Test
  public void preserveErrorsFromInnerCache() throws InterruptedException, IOException {
    ErroringArtifactCache inner = new ErroringArtifactCache();
    MultiArtifactCache cache = new MultiArtifactCache(ImmutableList.<ArtifactCache>of(inner));
    CacheResult result = cache.fetch(dummyRuleKey, dummyFile);
    assertSame(result.getType(), CacheResult.Type.ERROR);
    cache.close();
  }

}
