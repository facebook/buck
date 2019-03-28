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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.AbstractAsynchronousCache.CacheEventListener;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.ExplicitRunExecutorService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Test;

public class AbstractAsynchronousCacheTest {
  @Test
  public void testMultiFetchLimiting() throws Exception {
    ExplicitRunExecutorService service = new ExplicitRunExecutorService();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<ImmutableList<RuleKey>> requestedRuleKeys = new ArrayList<>();
    List<ImmutableSet<RuleKey>> checkedRuleKeys = new ArrayList<>();

    try (AbstractAsynchronousCache cache =
        new RequestedKeyRecordingAsynchronousCache(
            service, filesystem, requestedRuleKeys, checkedRuleKeys, 3, 3, false)) {

      List<ListenableFuture<CacheResult>> results = new ArrayList<>();
      List<RuleKey> keys = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        RuleKey key = new RuleKey(HashCode.fromInt(i));
        keys.add(key);
        results.add(
            cache.fetchAsync(null, key, LazyPath.ofInstance(filesystem.getPath("path" + i))));
      }

      service.run();

      for (ListenableFuture<CacheResult> future : results) {
        assertTrue(future.isDone());
        assertTrue(future.get().getType().isSuccess());
      }

      assertEquals(0, checkedRuleKeys.size());

      assertEquals(10, requestedRuleKeys.size());

      // The first couple should be limited by the multiFetchLimit.
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(0), keys.get(1), keys.get(2)), requestedRuleKeys.get(0));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(3), keys.get(4), keys.get(5)), requestedRuleKeys.get(1));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(6), keys.get(7), keys.get(8)), requestedRuleKeys.get(2));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(9), keys.get(1), keys.get(2)), requestedRuleKeys.get(3));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(4), keys.get(5), keys.get(7)), requestedRuleKeys.get(4));

      // At this point, there's just 5 keys left, and so it's limited by the concurrency.
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(8), keys.get(1)), requestedRuleKeys.get(5));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(2), keys.get(5)), requestedRuleKeys.get(6));
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(keys.get(7), keys.get(1)), requestedRuleKeys.get(7));

      // And finally, there's less than concurrency left and it'll go to fetch() instead of
      // multiFetch().
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(5)), requestedRuleKeys.get(8));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(1)), requestedRuleKeys.get(9));
    }
  }

  @Test
  public void testSkipPendingAsyncFetchRequests() throws ExecutionException, InterruptedException {
    ExplicitRunExecutorService service = new ExplicitRunExecutorService();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<ImmutableList<RuleKey>> requestedRuleKeys = new ArrayList<>();
    List<ImmutableSet<RuleKey>> checkedRuleKeys = new ArrayList<>();

    try (AbstractAsynchronousCache cache =
        new RequestedKeyRecordingAsynchronousCache(
            service, filesystem, requestedRuleKeys, checkedRuleKeys, 3, 3, false)) {

      // Make an async fetch request and allow it to run on the Executor
      ListenableFuture<CacheResult> fetchRequestOne =
          cache.fetchAsync(
              null,
              new RuleKey(HashCode.fromInt(1)),
              LazyPath.ofInstance(filesystem.getPath("path_one")));

      service.runOnce();
      CacheResult cacheResultOne = fetchRequestOne.get();
      assertTrue(cacheResultOne.getType().isSuccess());

      // Make an async fetch request, tell the cache to skip all pending requests, then
      // run the request on the Executor => it should be skipped
      ListenableFuture<CacheResult> fetchRequestTwo =
          cache.fetchAsync(
              null,
              new RuleKey(HashCode.fromInt(2)),
              LazyPath.ofInstance(filesystem.getPath("path_two")));
      cache.skipPendingAndFutureAsyncFetches();

      service.runOnce();
      CacheResult cacheResultTwo = fetchRequestTwo.get();
      assertEquals(CacheResultType.SKIPPED, cacheResultTwo.getType());

      // Make a further request and ensure it also gets skipped.
      ListenableFuture<CacheResult> fetchRequestThree =
          cache.fetchAsync(
              null,
              new RuleKey(HashCode.fromInt(3)),
              LazyPath.ofInstance(filesystem.getPath("path_three")));

      service.runOnce();
      CacheResult cacheResultThree = fetchRequestThree.get();
      assertEquals(CacheResultType.SKIPPED, cacheResultThree.getType());
    }
  }

  @Test
  public void testMultiCheck() throws Exception {
    ExplicitRunExecutorService service = new ExplicitRunExecutorService();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<ImmutableList<RuleKey>> requestedRuleKeys = new ArrayList<>();
    List<ImmutableSet<RuleKey>> checkedRuleKeys = new ArrayList<>();

    try (AbstractAsynchronousCache cache =
        new RequestedKeyRecordingAsynchronousCache(
            service, filesystem, requestedRuleKeys, checkedRuleKeys, 0, 1, true)) {

      List<ListenableFuture<CacheResult>> results = new ArrayList<>();
      List<RuleKey> keys = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        RuleKey key = new RuleKey(HashCode.fromInt(i));
        keys.add(key);
        results.add(
            cache.fetchAsync(null, key, LazyPath.ofInstance(filesystem.getPath("path" + i))));
      }

      service.run();

      for (int i = 0; i < results.size(); i++) {
        ListenableFuture<CacheResult> future = results.get(i);
        assertTrue(future.isDone());
        if (i < 6) {
          assertTrue(future.get().getType().isSuccess());
        } else {
          assertFalse(future.get().getType().isSuccess());
        }
      }

      assertEquals(1, checkedRuleKeys.size());
      assertEquals(6, requestedRuleKeys.size());

      // Validate we first check if all the keys are present in the cache.
      MoreAsserts.assertIterablesEquals(
          ImmutableList.of(
              keys.get(0),
              keys.get(1),
              keys.get(2),
              keys.get(3),
              keys.get(4),
              keys.get(5),
              keys.get(6),
              keys.get(7),
              keys.get(8),
              keys.get(9)),
          checkedRuleKeys.get(0));

      // Each of the present keys should be download individually.
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(0)), requestedRuleKeys.get(0));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(1)), requestedRuleKeys.get(1));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(2)), requestedRuleKeys.get(2));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(3)), requestedRuleKeys.get(3));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(4)), requestedRuleKeys.get(4));
      MoreAsserts.assertIterablesEquals(ImmutableList.of(keys.get(5)), requestedRuleKeys.get(5));
    }
  }

  private static class NoOpEventListener implements AbstractAsynchronousCache.CacheEventListener {
    @Override
    public AbstractAsynchronousCache.StoreEvents storeScheduled(
        ArtifactInfo info, long artifactSizeBytes) {
      return () ->
          new AbstractAsynchronousCache.StoreEvents.StoreRequestEvents() {
            @Override
            public void finished(StoreResult result) {}

            @Override
            public void failed(IOException e, String errorMessage) {}
          };
    }

    @Override
    public void fetchScheduled(RuleKey ruleKey) {}

    @Override
    public CacheEventListener.FetchRequestEvents fetchStarted(BuildTarget target, RuleKey ruleKey) {
      return new FetchRequestEvents() {
        @Override
        public void finished(FetchResult result) {}

        @Override
        public void failed(IOException e, String errorMessage, CacheResult result) {}
      };
    }

    @Override
    public CacheEventListener.MultiFetchRequestEvents multiFetchStarted(
        ImmutableList<BuildTarget> targets, ImmutableList<RuleKey> keys) {
      return new MultiFetchRequestEvents() {
        @Override
        public void skipped(int keyIndex) {}

        @Override
        public void finished(int keyIndex, FetchResult thisResult) {}

        @Override
        public void failed(int keyIndex, IOException e, String msg, CacheResult result) {}

        @Override
        public void close() {}
      };
    }
  }

  private static class RequestedKeyRecordingAsynchronousCache extends AbstractAsynchronousCache {
    private final ImmutableSet<RuleKey> matching =
        IntStream.rangeClosed(0, 5)
            .mapToObj(i -> new RuleKey(HashCode.fromInt(i)))
            .collect(ImmutableSet.toImmutableSet());
    private final List<ImmutableList<RuleKey>> requestedRuleKeys;
    private final List<ImmutableSet<RuleKey>> checkedRuleKeys;
    private int multiFetchLimit;
    private int concurrency;
    private boolean isMultiCheckEnabled;

    public RequestedKeyRecordingAsynchronousCache(
        ExplicitRunExecutorService service,
        ProjectFilesystem filesystem,
        List<ImmutableList<RuleKey>> requestedRuleKeys,
        List<ImmutableSet<RuleKey>> checkedRuleKeys,
        int multiFetchLimit,
        int concurrency,
        boolean isMultiCheckEnabled) {
      super(
          "fake",
          ArtifactCacheMode.dir,
          CacheReadMode.READWRITE,
          service,
          service,
          new NoOpEventListener(),
          Optional.empty(),
          filesystem);
      this.requestedRuleKeys = requestedRuleKeys;
      this.checkedRuleKeys = checkedRuleKeys;
      this.multiFetchLimit = multiFetchLimit;
      this.concurrency = concurrency;
      this.isMultiCheckEnabled = isMultiCheckEnabled;
    }

    @Override
    protected FetchResult fetchImpl(
        @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
      requestedRuleKeys.add(ImmutableList.of(ruleKey));
      return hit();
    }

    @Override
    protected MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> ruleKeys) {
      checkedRuleKeys.add(ruleKeys);
      ImmutableMap.Builder<RuleKey, CacheResult> cacheResults = ImmutableMap.builder();
      for (RuleKey ruleKey : ruleKeys) {
        CacheResult cacheResult;
        if (matching.contains(ruleKey)) {
          cacheResult = CacheResult.hit(getName(), getMode());
        } else {
          cacheResult = CacheResult.miss();
        }
        cacheResults.put(ruleKey, cacheResult);
      }
      return MultiContainsResult.builder().setCacheResults(cacheResults.build()).build();
    }

    private FetchResult hit() {
      return FetchResult.builder().setCacheResult(CacheResult.hit(getName(), getMode())).build();
    }

    private FetchResult skip() {
      return FetchResult.builder().setCacheResult(CacheResult.skipped()).build();
    }

    @Override
    protected StoreResult storeImpl(ArtifactInfo info, Path file) {
      return null;
    }

    @Override
    protected CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) {
      throw new RuntimeException("Delete operation is not supported");
    }

    @Override
    protected MultiFetchResult multiFetchImpl(
        Iterable<AbstractAsynchronousCache.FetchRequest> requests) {
      List<FetchResult> result = new ArrayList<>();
      result.add(hit());
      ImmutableList<RuleKey> keys =
          RichStream.from(requests)
              .map(FetchRequest::getRuleKey)
              .collect(ImmutableList.toImmutableList());
      requestedRuleKeys.add(keys);
      while (result.size() < keys.size()) {
        result.add(skip());
      }
      return MultiFetchResult.of(ImmutableList.copyOf(result));
    }

    @Override
    public void close() {}

    @Override
    protected int getMultiFetchBatchSize(int pendingRequestsSize) {
      return Math.min(multiFetchLimit, 1 + pendingRequestsSize / concurrency);
    }

    @Override
    protected boolean isMultiCheckEnabled() {
      return isMultiCheckEnabled;
    }
  }
}
