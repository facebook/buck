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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class TwoLevelArtifactCacheDecoratorTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static final RuleKey dummyRuleKey =
      new RuleKey("76b1c1beae69428db2d1befb31cf743ac8ce90df");
  private static final RuleKey dummyRuleKey2 =
      new RuleKey("1111111111111111111111111111111111111111");

  @Test
  public void testCacheFetch() throws IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
        TwoLevelArtifactCacheDecorator twoLevelCache =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ true,
                /* minimumTwoLevelStoredArtifactSize */ 0L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.empty())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      assertThat(
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey, dummyFile)).getType(),
          Matchers.equalTo(CacheResultType.MISS));

      twoLevelCache.store(
          ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      assertThat(
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey, dummyFile)).getType(),
          Matchers.equalTo(CacheResultType.HIT));

      twoLevelCache.store(
          ArtifactInfo.builder().addRuleKeys(dummyRuleKey2).build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));

      assertThat(
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey2, dummyFile)).getType(),
          Matchers.equalTo(CacheResultType.HIT));
      assertThat(inMemoryArtifactCache.getArtifactCount(), Matchers.equalTo(3));
    }
  }

  @Test
  public void testResultDoesntHaveAddedMetadata() throws IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
        TwoLevelArtifactCacheDecorator twoLevelCache =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ true,
                /* minimumTwoLevelStoredArtifactSize */ 0L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.empty())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());
      twoLevelCache.store(
          ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      CacheResult cacheResult =
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey, dummyFile));
      assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.HIT));
      assertThat(
          cacheResult.getMetadata().keySet(),
          Matchers.not(Matchers.contains(TwoLevelArtifactCacheDecorator.METADATA_KEY)));
    }
  }

  private void testStoreThresholds(int artifactSize, int expectedArtifactsInCache)
      throws IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
        TwoLevelArtifactCacheDecorator twoLevelCache =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ true,
                /* minimumTwoLevelStoredArtifactSize */ 5L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.of(10L))) {

      LazyPath lazyPath = LazyPath.ofInstance(tmp.newFile());
      Files.write(lazyPath.get(), new byte[artifactSize]);

      twoLevelCache.store(
          ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
          BorrowablePath.notBorrowablePath(lazyPath.get()));
      assertThat(
          inMemoryArtifactCache.getArtifactCount(), Matchers.equalTo(expectedArtifactsInCache));
    }
  }

  @Test
  public void noTwoLevelStoreWhenFileSizeBelowThreshold() throws Exception {
    testStoreThresholds(/* artifactSize */ 3, /* expectedArtifactsInCache */ 1);
  }

  @Test
  public void twoLeveLStoreWhenFileSizeInRange() throws Exception {
    testStoreThresholds(/* artifactSize */ 6, /* expectedArtifactsInCache */ 2);
  }

  @Test
  public void noTwoLevelStoreWhenFileSizeAboveMax() throws Exception {
    testStoreThresholds(/* artifactSize */ 11, /* expectedArtifactsInCache */ 1);
  }

  @Test
  public void testMetadataIsNotShared() throws IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
        TwoLevelArtifactCacheDecorator twoLevelCache =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ true,
                /* minimumTwoLevelStoredArtifactSize */ 0L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.empty())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      String testMetadataKey = "testMetaKey";
      twoLevelCache.store(
          ArtifactInfo.builder()
              .addRuleKeys(dummyRuleKey)
              .setMetadata(ImmutableMap.of(testMetadataKey, "value1"))
              .build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      twoLevelCache.store(
          ArtifactInfo.builder()
              .addRuleKeys(dummyRuleKey2)
              .setMetadata(ImmutableMap.of(testMetadataKey, "value2"))
              .build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));

      CacheResult fetch1 =
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey, dummyFile));
      CacheResult fetch2 =
          Futures.getUnchecked(twoLevelCache.fetchAsync(null, dummyRuleKey2, dummyFile));
      // The metadata shouldn't be shared
      assertNotEquals(
          fetch1.getMetadata().get(testMetadataKey), fetch2.getMetadata().get(testMetadataKey));
    }
  }

  @Test
  public void testCanRead2LStoresIfStoresDisabled() throws IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
        TwoLevelArtifactCacheDecorator twoLevelCache =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ true,
                /* minimumTwoLevelStoredArtifactSize */ 0L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.empty());
        TwoLevelArtifactCacheDecorator twoLevelCacheNoStore =
            new TwoLevelArtifactCacheDecorator(
                inMemoryArtifactCache,
                TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
                BuckEventBusForTests.newInstance(),
                /* performTwoLevelStores */ false,
                /* minimumTwoLevelStoredArtifactSize */ 0L,
                /* maximumTwoLevelStoredArtifactSize */ Optional.empty())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      twoLevelCache.store(
          ArtifactInfo.builder().addRuleKeys(dummyRuleKey).build(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      assertThat(inMemoryArtifactCache.getArtifactCount(), Matchers.equalTo(2));

      assertThat(
          Futures.getUnchecked(twoLevelCacheNoStore.fetchAsync(null, dummyRuleKey, dummyFile))
              .getType(),
          Matchers.equalTo(CacheResultType.HIT));
    }
  }
}
