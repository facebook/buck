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

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class SQLiteArtifactCacheBenchmark {
  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  @Param({"1000", "10000", "100000"})
  private int opCount = 100;

  @Param({"5", "10"})
  private int threadCount = 2;

  private static final Random random = new Random(12345);
  private static final long MAX_INLINED_BYTES = 1024;

  private ProjectFilesystem filesystem;
  private List<RuleKey> ruleKeys;
  private List<RuleKey> contentHashes;
  private List<ArtifactInfo> metadataInfo;
  private List<ArtifactInfo> contentInfo;
  private Path emptyFile;
  private Path inlinedFile;
  private Path largeFile;

  private Path cacheDir;
  private LazyPath output;
  private SQLiteArtifactCache artifactCache;
  private ListeningExecutorService executor;

  @Before
  public void setUp() throws InterruptedException, IOException, SQLException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot());

    emptyFile = tmpDir.newFile(".empty");
    inlinedFile = tmpDir.newFile(".inlined");
    largeFile = tmpDir.newFile(".large");
    Files.write(inlinedFile, new byte[] {'a', 'r', 't', 'i', 'f', 'a', 'c', 't'});
    for (int i = 0; i < MAX_INLINED_BYTES; i++) {
      Files.write(largeFile, new byte[] {'b', 'i', 'g'});
    }

    cacheDir = tmpDir.newFolder();
    output = LazyPath.ofInstance(cacheDir.resolve(".output"));

    setUpBenchmark();
  }

  @BeforeExperiment
  private void setUpBenchmark() throws IOException, SQLException {
    artifactCache = cache(Optional.of(1024 * 1024 * 1024L));
    byte[] randomRuleKey = new byte[16];

    ruleKeys = new ArrayList<>(opCount);
    contentHashes = new ArrayList<>(opCount);
    metadataInfo = new ArrayList<>(opCount);
    contentInfo = new ArrayList<>(opCount);

    for (int i = 0; i < opCount; i++) {
      random.nextBytes(randomRuleKey);
      RuleKey ruleKey = new RuleKey(HashCode.fromBytes(randomRuleKey));
      ruleKeys.add(ruleKey);
      metadataInfo.add(
          ArtifactInfo.builder()
              .addRuleKeys(ruleKey)
              .putMetadata(TwoLevelArtifactCacheDecorator.METADATA_KEY, "foo")
              .putMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleKey.toString())
              .putMetadata(BuildInfo.MetadataKey.TARGET, "bar")
              .build());

      random.nextBytes(randomRuleKey);
      RuleKey contentHash = new RuleKey(HashCode.fromBytes(randomRuleKey));
      contentHashes.add(contentHash);
      contentInfo.add(ArtifactInfo.builder().addRuleKeys(contentHash).build());
    }
  }

  @After
  @AfterExperiment
  public void tearDown() {
    artifactCache.close();
    executor.shutdown();
  }

  private SQLiteArtifactCache cache(Optional<Long> maxCacheSizeBytes)
      throws IOException, SQLException {
    return new SQLiteArtifactCache(
        "sqlite",
        filesystem,
        cacheDir,
        BuckEventBusForTests.newInstance(),
        maxCacheSizeBytes,
        Optional.of(MAX_INLINED_BYTES),
        CacheReadMode.READWRITE);
  }

  @Ignore
  @Test
  public void testSingleThreaded() {
    executor = MoreExecutors.newDirectExecutorService();
    runAllBenchmarks();
  }

  @Ignore
  @Test
  public void testMultiThreaded() {
    executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threadCount));
    runAllBenchmarks();
  }

  private void runAllBenchmarks() {
    benchMetadataStore();
    benchMetadataFetch();
    benchArtifactStore();
    benchArtifactFetch();
  }

  @Benchmark
  private void benchMetadataStore() {
    for (ArtifactInfo info : metadataInfo) {
      artifactCache.store(info, BorrowablePath.notBorrowablePath(emptyFile));
    }
  }

  @Benchmark
  private void benchMetadataFetch() {
    for (RuleKey key : ruleKeys) {
      Futures.getUnchecked(artifactCache.fetchAsync(key, output));
    }
  }

  @Benchmark
  private void benchArtifactStore() {
    for (int i = 0; i < contentInfo.size() / 2; i++) {
      artifactCache.store(contentInfo.get(i), BorrowablePath.notBorrowablePath(inlinedFile));
    }

    for (int i = contentInfo.size() / 2; i < contentInfo.size(); i++) {
      artifactCache.store(contentInfo.get(i), BorrowablePath.notBorrowablePath(largeFile));
    }
  }

  @Benchmark
  private void benchArtifactFetch() {
    for (RuleKey key : ruleKeys) {
      Futures.getUnchecked(artifactCache.fetchAsync(key, output));
    }
  }
}
