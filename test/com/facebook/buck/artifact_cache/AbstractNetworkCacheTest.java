/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfigurationSerializerForTests;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.util.concurrent.FakeListeningExecutorService;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;

public class AbstractNetworkCacheTest {

  @Test
  public void testBigArtifactIsNotStored() throws InterruptedException, ExecutionException {
    testStoreCall(0, Optional.of(10L), 11, 111);
  }

  @Test
  public void testBigArtifactIsStored() throws InterruptedException, ExecutionException {
    testStoreCall(2, Optional.of(10L), 5, 10);
  }

  @Test
  public void testBigArtifactIsStoredWhenMaxIsNotDefined()
      throws InterruptedException, ExecutionException {
    testStoreCall(4, Optional.empty(), 5, 10, 100, 1000);
  }

  private void testStoreCall(
      int expectStoreCallCount, Optional<Long> maxArtifactSizeBytes, int... artifactBytes)
      throws InterruptedException, ExecutionException {
    AtomicInteger storeCallCount = new AtomicInteger(0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ListeningExecutorService service =
        new FakeListeningExecutorService() {
          @Override
          public void execute(Runnable command) {
            command.run();
          }
        };

    HttpService httpService = new TestHttpService();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);

    AbstractNetworkCache cache =
        new AbstractNetworkCache(
            NetworkCacheArgs.builder()
                .setCacheName("AbstractNetworkCacheTest")
                .setCacheMode(ArtifactCacheMode.http)
                .setRepository("some_repository")
                .setScheduleType("some_schedule_type")
                .setFetchClient(httpService)
                .setStoreClient(httpService)
                .setCacheReadMode(CacheReadMode.READWRITE)
                .setTargetConfigurationSerializer(
                    TargetConfigurationSerializerForTests.create(cellPathResolver))
                .setUnconfiguredBuildTargetFactory(
                    target ->
                        new ParsingUnconfiguredBuildTargetFactory()
                            .create(cellPathResolver, target))
                .setProjectFilesystem(filesystem)
                .setBuckEventBus(BuckEventBusForTests.newInstance())
                .setHttpWriteExecutorService(service)
                .setHttpFetchExecutorService(service)
                .setErrorTextTemplate("super error message")
                .setErrorTextLimit(100)
                .setMaxStoreSizeBytes(maxArtifactSizeBytes)
                .build()) {
          @Override
          protected FetchResult fetchImpl(
              @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
            return null;
          }

          @Override
          protected MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> ruleKeys) {
            return null;
          }

          @Override
          protected StoreResult storeImpl(ArtifactInfo info, Path file) {
            storeCallCount.incrementAndGet();
            return StoreResult.builder().build();
          }

          @Override
          protected MultiFetchResult multiFetchImpl(
              Iterable<AbstractAsynchronousCache.FetchRequest> requests) {
            return null;
          }

          @Override
          protected CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) {
            throw new RuntimeException("Delete operation is not supported");
          }
        };

    for (int bytes : artifactBytes) {
      Path path = filesystem.getPathForRelativePath("topspin_" + this.getClass().getName());
      filesystem.writeBytesToPath(new byte[bytes], path);
      ListenableFuture<Void> future =
          cache.store(ArtifactInfo.builder().build(), BorrowablePath.notBorrowablePath(path));
      future.get();
    }

    Assert.assertEquals(expectStoreCallCount, storeCallCount.get());
  }
}
