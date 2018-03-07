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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class MultiArtifactCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static final RuleKey dummyRuleKey =
      new RuleKey("76b1c1beae69428db2d1befb31cf743ac8ce90df");
  private static final RuleKey dummyRuleKey2 =
      new RuleKey("00000000ae69428db2d1befb31cf743a00000000");
  private static final LazyPath dummyFile = LazyPath.ofInstance(Paths.get("dummy"));

  // An cache which always returns errors from fetching.
  class ErroringArtifactCache extends NoopArtifactCache {
    @Override
    public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
      return Futures.immediateFuture(CacheResult.error("cache", ArtifactCacheMode.http, "error"));
    }
  }

  @Test
  public void testCacheFetch() throws IOException {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(dummyArtifactCache1, dummyArtifactCache2));

    assertEquals(
        "Fetch should fail",
        CacheResultType.MISS,
        Futures.getUnchecked(multiArtifactCache.fetchAsync(dummyRuleKey, dummyFile)).getType());

    dummyArtifactCache1.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
        BorrowablePath.notBorrowablePath(dummyFile.get()));
    assertEquals(
        "Fetch should succeed after store",
        CacheResultType.HIT,
        Futures.getUnchecked(multiArtifactCache.fetchAsync(dummyRuleKey, dummyFile)).getType());

    dummyArtifactCache1.reset();
    dummyArtifactCache2.reset();
    dummyArtifactCache2.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
        BorrowablePath.notBorrowablePath(dummyFile.get()));
    assertEquals(
        "Fetch should succeed after store",
        CacheResultType.HIT,
        Futures.getUnchecked(multiArtifactCache.fetchAsync(dummyRuleKey, dummyFile)).getType());

    multiArtifactCache.close();
  }

  @Test
  public void testCacheMultiContains() throws IOException {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(dummyArtifactCache1, dummyArtifactCache2));

    Map<RuleKey, CacheResult> results =
        Futures.getUnchecked(
            multiArtifactCache.multiContainsAsync(ImmutableSet.of(dummyRuleKey, dummyRuleKey2)));
    assertEquals(
        "Contains should fail",
        ImmutableMap.of(dummyRuleKey, CacheResult.miss(), dummyRuleKey2, CacheResult.miss()),
        results);

    dummyArtifactCache1.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
        BorrowablePath.notBorrowablePath(dummyFile.get()));
    results =
        Futures.getUnchecked(
            multiArtifactCache.multiContainsAsync(ImmutableSet.of(dummyRuleKey, dummyRuleKey2)));
    assertEquals(
        "Contains should succeed for first rulekey (present in store 1)",
        CacheResultType.CONTAINS,
        results.get(dummyRuleKey).getType());
    assertEquals(
        "Contains should fail for second rulekey (not yet written)",
        CacheResultType.MISS,
        results.get(dummyRuleKey2).getType());

    dummyArtifactCache2.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey2).build(),
        BorrowablePath.notBorrowablePath(dummyFile.get()));
    results =
        Futures.getUnchecked(
            multiArtifactCache.multiContainsAsync(ImmutableSet.of(dummyRuleKey, dummyRuleKey2)));
    assertEquals(
        "Contains should succeed for first rulekey (present in store 1)",
        CacheResultType.CONTAINS,
        results.get(dummyRuleKey).getType());
    assertEquals(
        "Contains should succeed for second rulekey (present in store 2)",
        CacheResultType.CONTAINS,
        results.get(dummyRuleKey2).getType());

    dummyArtifactCache1.reset();
    results =
        Futures.getUnchecked(
            multiArtifactCache.multiContainsAsync(ImmutableSet.of(dummyRuleKey, dummyRuleKey2)));
    assertEquals(
        "Contains should fail for first rulekey (removed from store 1)",
        CacheResultType.MISS,
        results.get(dummyRuleKey).getType());
    assertEquals(
        "Contains should succeed for second rulekey (present in store 2)",
        CacheResultType.CONTAINS,
        results.get(dummyRuleKey2).getType());

    multiArtifactCache.close();
  }

  @Test
  public void testPropagateOnlyCacheStore()
      throws InterruptedException, IOException, ExecutionException {
    DummyArtifactCache dummyArtifactCache1 =
        new DummyArtifactCache() {
          @Override
          public CacheReadMode getCacheReadMode() {
            return CacheReadMode.PASSTHROUGH;
          }
        };
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(dummyArtifactCache1, dummyArtifactCache2));

    assertEquals(CacheReadMode.READWRITE, multiArtifactCache.getCacheReadMode());

    multiArtifactCache
        .store(
            ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
            BorrowablePath.notBorrowablePath(dummyFile.get()))
        .get();

    assertEquals(
        "This cache is PASSTHROUGH, store on the mulit-cache should not write to it",
        CacheResultType.MISS,
        Futures.getUnchecked(dummyArtifactCache1.fetchAsync(dummyRuleKey, dummyFile)).getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(dummyArtifactCache2.fetchAsync(dummyRuleKey, dummyFile)).getType());

    multiArtifactCache.close();
  }

  @Test
  public void testPropagateOnlyCacheGet()
      throws InterruptedException, IOException, ExecutionException {
    DummyArtifactCache dummyArtifactCache1 =
        new DummyArtifactCache() {
          @Override
          public CacheReadMode getCacheReadMode() {
            return CacheReadMode.PASSTHROUGH;
          }
        };
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(dummyArtifactCache1, dummyArtifactCache2));

    dummyArtifactCache2
        .store(
            ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
            BorrowablePath.notBorrowablePath(dummyFile.get()))
        .get();

    assertEquals(
        "Fetch should find artifact that's present in one of the caches.",
        CacheResultType.HIT,
        Futures.getUnchecked(multiArtifactCache.fetchAsync(dummyRuleKey, dummyFile)).getType());
    assertEquals(
        "Fetch should have propagated the artifact.",
        CacheResultType.HIT,
        Futures.getUnchecked(dummyArtifactCache1.fetchAsync(dummyRuleKey, dummyFile)).getType());

    multiArtifactCache.close();
  }

  @Test
  public void testCacheStore() throws IOException {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(dummyArtifactCache1, dummyArtifactCache2));

    multiArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
        BorrowablePath.notBorrowablePath(dummyFile.get()));

    assertEquals(
        "MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache1.storeKey,
        dummyRuleKey);
    assertEquals(
        "MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache2.storeKey,
        dummyRuleKey);

    multiArtifactCache.close();
  }

  private static class SimpleArtifactCache implements ArtifactCache {
    private final ProjectFilesystem filesystem;
    @Nullable public AtomicReference<RuleKey> storedKey;

    public SimpleArtifactCache(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      this.storedKey = new AtomicReference<>(null);
    }

    @Override
    public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
      return Futures.immediateFuture(fetch(ruleKey, output));
    }

    @Override
    public void skipPendingAndFutureAsyncFetches() {
      // Async requests are not supported by SimpleArtifactCache, so do nothing
    }

    private CacheResult fetch(RuleKey ruleKey, LazyPath output) {
      if (ruleKey.equals(storedKey.get())) {
        try {
          filesystem.touch(output.get());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return CacheResult.hit("cache", ArtifactCacheMode.http);
      } else {
        return CacheResult.miss();
      }
    }

    @Override
    public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
      if (output.canBorrow()) {
        try {
          filesystem.deleteFileAtPath(output.getPath());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      storedKey.set(Iterables.getFirst(info.getRuleKeys(), null));
      return Futures.immediateFuture(null);
    }

    @Override
    public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
        ImmutableSet<RuleKey> ruleKeys) {
      RuleKey storedKeyInstance = storedKey.get();
      return Futures.immediateFuture(
          Maps.toMap(
              ruleKeys,
              ruleKey ->
                  ruleKey.equals(storedKeyInstance)
                      ? CacheResult.contains("cache", ArtifactCacheMode.http)
                      : CacheResult.miss()));
    }

    @Override
    public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
      throw new RuntimeException("Delete operation is not supported");
    }

    @Override
    public CacheReadMode getCacheReadMode() {
      return CacheReadMode.READWRITE;
    }

    @Override
    public void close() {}
  }

  @Test
  public void testCacheStoreWithBorrowablePathConsumingCache() {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path fetchFile = filesystem.resolve("fetch_file");

    SimpleArtifactCache fakeDirCache = new SimpleArtifactCache(filesystem);
    SimpleArtifactCache fakeReadOnlyCache =
        new SimpleArtifactCache(filesystem) {
          @Override
          public CacheReadMode getCacheReadMode() {
            return CacheReadMode.READONLY;
          }
        };
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(fakeDirCache, fakeReadOnlyCache));

    fakeReadOnlyCache.storedKey.set(dummyRuleKey);

    Futures.getUnchecked(
        multiArtifactCache.fetchAsync(dummyRuleKey, LazyPath.ofInstance(fetchFile)));

    assertThat(
        "The .get() call should not delete the path it's fetching.",
        filesystem.exists(fetchFile),
        Matchers.is(true));

    multiArtifactCache.close();
  }

  @Test
  public void preserveErrorsFromInnerCache() {
    ErroringArtifactCache inner = new ErroringArtifactCache();
    MultiArtifactCache cache = new MultiArtifactCache(ImmutableList.of(inner));
    CacheResult result = Futures.getUnchecked(cache.fetchAsync(dummyRuleKey, dummyFile));
    assertThat(result.cacheMode(), Matchers.equalTo(Optional.of(ArtifactCacheMode.http)));
    assertSame(result.getType(), CacheResultType.ERROR);
    cache.close();
  }

  @Test
  public void cacheFetchPushesMetadataToHigherCache() throws Exception {
    InMemoryArtifactCache cache1 = new InMemoryArtifactCache();
    InMemoryArtifactCache cache2 = new InMemoryArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(cache1, cache2));

    LazyPath output = LazyPath.ofInstance(tmp.newFile());

    ImmutableMap<String, String> metadata = ImmutableMap.of("hello", "world");
    cache2.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).setMetadata(metadata).build(),
        new byte[0]);
    Futures.getUnchecked(multiArtifactCache.fetchAsync(dummyRuleKey, output));

    CacheResult result = Futures.getUnchecked(cache1.fetchAsync(dummyRuleKey, output));
    assertThat(result.getType(), Matchers.equalTo(CacheResultType.HIT));
    assertThat(result.cacheMode(), Matchers.equalTo(Optional.of(ArtifactCacheMode.dir)));
    assertThat(result.getMetadata(), Matchers.equalTo(metadata));

    multiArtifactCache.close();
  }

  @Test
  public void cacheAsyncFetchPushesMetadataToHigherCache() throws Exception {
    InMemoryArtifactCache cache1 = new InMemoryArtifactCache();
    InMemoryArtifactCache cache2 = new InMemoryArtifactCache();
    MultiArtifactCache multiArtifactCache =
        new MultiArtifactCache(ImmutableList.of(cache1, cache2));

    LazyPath output = LazyPath.ofInstance(tmp.newFile());

    ImmutableMap<String, String> metadata = ImmutableMap.of("hello", "world");
    cache2.store(
        ArtifactInfo.builder().addRuleKeys(dummyRuleKey).setMetadata(metadata).build(),
        new byte[0]);
    multiArtifactCache.fetchAsync(dummyRuleKey, output).get();

    CacheResult result = Futures.getUnchecked(cache1.fetchAsync(dummyRuleKey, output));
    assertThat(result.getType(), Matchers.equalTo(CacheResultType.HIT));
    assertThat(result.cacheMode(), Matchers.equalTo(Optional.of(ArtifactCacheMode.dir)));
    assertThat(result.getMetadata(), Matchers.equalTo(metadata));

    multiArtifactCache.close();
  }
}
