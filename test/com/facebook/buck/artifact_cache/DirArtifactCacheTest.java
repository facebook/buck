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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.DirectoryCleaner;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DirArtifactCacheTest {
  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  private FileHashCache fileHashCache = new DummyFileHashCache();

  private DirArtifactCache dirArtifactCache;
  private Path cacheDir;
  private DefaultProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws IOException {
    cacheDir = tmpDir.newFolder();
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(cacheDir);
  }

  @After
  public void tearDown() {
    if (dirArtifactCache != null) {
      dirArtifactCache.close();
    }
  }

  @Test
  public void testCacheCreation() throws IOException {
    dirArtifactCache = newDirArtifactCache(Optional.of(0L), CacheReadMode.READWRITE);
  }

  @Test
  public void testCacheFetchMiss() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    Optional<Long> maxCacheSizeBytes = Optional.of(0L);
    dirArtifactCache = newDirArtifactCache(maxCacheSizeBytes, CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder).build(inputRuleX);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
  }

  @Test
  public void testCacheStoreAndFetchHit() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder).build(inputRuleX);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));

    // Test that artifact overwrite works.
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));

    // Test that artifact creation works.
    Files.delete(fileX);
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
  }

  @Test
  public void testCacheContainsMiss() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.of(0L), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder).build(inputRuleX);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(dirArtifactCache.multiContainsAsync(ImmutableSet.of(ruleKeyX)))
            .get(ruleKeyX)
            .getType());
  }

  @Test
  public void testCacheStoreAndContainsHit() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder).build(inputRuleX);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));

    assertEquals(
        CacheResultType.CONTAINS,
        Futures.getUnchecked(dirArtifactCache.multiContainsAsync(ImmutableSet.of(ruleKeyX)))
            .get(ruleKeyX)
            .getType());
  }

  @Test
  public void testCacheStoreOverwrite() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder).build(inputRuleX);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));

    // Overwrite.
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));

    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
  }

  @Test
  public void testCacheStoresAndFetchHits() throws IOException {
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ));

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
    assertEquals(inputRuleY, new BuildRuleForTest(fileY));
    assertEquals(inputRuleZ, new BuildRuleForTest(fileZ));

    ImmutableList<Path> cachedFiles = ImmutableList.copyOf(dirArtifactCache.getAllFilesInCache());
    assertEquals(6, cachedFiles.size());

    ImmutableSet<String> filenames =
        cachedFiles.stream()
            .map(input -> input.getFileName().toString())
            .collect(ImmutableSet.toImmutableSet());

    for (RuleKey ruleKey : ImmutableSet.of(ruleKeyX, ruleKeyY, ruleKeyZ)) {
      assertThat(filenames, Matchers.hasItem(ruleKey.toString()));
      assertThat(filenames, Matchers.hasItem(ruleKey + ".metadata"));
    }
  }

  @Test
  public void testCacheMultiStoresAndContainsHits() throws IOException {
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    dirArtifactCache.store(
        ImmutableList.of(
            new Pair<>(
                ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
                BorrowablePath.notBorrowablePath(fileX)),
            new Pair<>(
                ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
                BorrowablePath.notBorrowablePath(fileY)),
            new Pair<>(
                ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
                BorrowablePath.notBorrowablePath(fileZ))));

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
    assertEquals(inputRuleY, new BuildRuleForTest(fileY));
    assertEquals(inputRuleZ, new BuildRuleForTest(fileZ));

    ImmutableList<Path> cachedFiles = ImmutableList.copyOf(dirArtifactCache.getAllFilesInCache());
    assertEquals(6, cachedFiles.size());

    ImmutableSet<String> filenames =
        cachedFiles.stream()
            .map(input -> input.getFileName().toString())
            .collect(ImmutableSet.toImmutableSet());

    for (RuleKey ruleKey : ImmutableSet.of(ruleKeyX, ruleKeyY, ruleKeyZ)) {
      assertThat(filenames, Matchers.hasItem(ruleKey.toString()));
      assertThat(filenames, Matchers.hasItem(ruleKey + ".metadata"));
    }
  }

  @Test
  public void testCacheStoresAndMultiContainsHits() throws IOException {

    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    assertEquals(
        ImmutableList.of(CacheResultType.MISS, CacheResultType.MISS, CacheResultType.MISS),
        Futures.getUnchecked(
                dirArtifactCache.multiContainsAsync(ImmutableSet.of(ruleKeyX, ruleKeyY, ruleKeyZ)))
            .values().stream()
            .map(CacheResult::getType)
            .collect(ImmutableList.toImmutableList()));

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ));

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(
        ImmutableList.of(
            CacheResultType.CONTAINS, CacheResultType.CONTAINS, CacheResultType.CONTAINS),
        Futures.getUnchecked(
                dirArtifactCache.multiContainsAsync(ImmutableSet.of(ruleKeyX, ruleKeyY, ruleKeyZ)))
            .values().stream()
            .map(CacheResult::getType)
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void testCacheStoresAndBorrowsPaths() throws IOException {
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    assertFalse(inputRuleX.equals(inputRuleY));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(), BorrowablePath.borrowablePath(fileX));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY));

    assertThat(Files.exists(fileX), Matchers.is(false));
    assertThat(Files.exists(fileY), Matchers.is(true));
  }

  @Test
  public void testNoStoreMisses() throws IOException {
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.of(0L), CacheReadMode.READONLY);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ));

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());

    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
    assertEquals(inputRuleY, new BuildRuleForTest(fileY));
    assertEquals(inputRuleZ, new BuildRuleForTest(fileZ));

    assertEquals(0, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteNothing() throws IOException {
    Path fileX = cacheDir.resolve("x");
    Path fileY = cacheDir.resolve("y");
    Path fileZ = cacheDir.resolve("z");

    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.of(1024L), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteNothingAbsentLimit() throws IOException {
    Path fileX = cacheDir.resolve("x");
    Path fileY = cacheDir.resolve("y");
    Path fileZ = cacheDir.resolve("z");

    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteSome() throws IOException {
    Path fileW = cacheDir.resolve("11").resolve("11").resolve("w");
    Path fileX = cacheDir.resolve("22").resolve("22").resolve("x");
    Path fileY = cacheDir.resolve("33").resolve("33").resolve("y");
    Path fileZ = cacheDir.resolve("44").resolve("44").resolve("z");

    Files.createDirectories(fileW.getParent());
    Files.createDirectories(fileX.getParent());
    Files.createDirectories(fileY.getParent());
    Files.createDirectories(fileZ.getParent());

    dirArtifactCache = newDirArtifactCache(Optional.of(3L), CacheReadMode.READWRITE);

    Files.write(fileW, "w".getBytes(UTF_8));
    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    Files.setAttribute(fileW, "lastAccessTime", FileTime.fromMillis(9000));
    Files.setAttribute(fileX, "lastAccessTime", FileTime.fromMillis(0));
    Files.setAttribute(fileY, "lastAccessTime", FileTime.fromMillis(1000));
    Files.setAttribute(fileZ, "lastAccessTime", FileTime.fromMillis(2000));

    // On some filesystems, setting creationTime silently fails. So don't test that here.

    assertEquals(4, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    List<Path> filesInCache = dirArtifactCache.getAllFilesInCache();
    assertEquals(ImmutableSet.of(fileZ, fileW), ImmutableSet.copyOf(filesInCache));
  }

  private DirectoryCleaner.PathStats fakePathStats(long creationTime, long lastAccessTime) {
    return new DirectoryCleaner.PathStats(null, 0, creationTime, lastAccessTime);
  }

  @Test
  public void testDirectoryCleanerPathSelector() throws IOException {
    dirArtifactCache = newDirArtifactCache(Optional.of(3L), CacheReadMode.READWRITE);

    DirectoryCleaner.PathSelector pathSelector = dirArtifactCache.getDirectoryCleanerPathSelector();

    assertTrue(pathSelector.comparePaths(fakePathStats(0, 0), fakePathStats(0, 10)) < 0);
    assertTrue(pathSelector.comparePaths(fakePathStats(0, 10), fakePathStats(10, 10)) < 0);
    assertSame(pathSelector.comparePaths(fakePathStats(20, 10), fakePathStats(20, 10)), 0);
    assertTrue(pathSelector.comparePaths(fakePathStats(0, 20), fakePathStats(10, 10)) > 0);
    assertTrue(pathSelector.comparePaths(fakePathStats(20, 10), fakePathStats(10, 10)) > 0);
  }

  @Test
  public void testDeleteAfterStoreIfFull() throws IOException {
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    // The reason max size is 9 bytes is because a 1-byte entry actually takes 6 bytes to store.
    // If the cache trims the size down to 2/3 (6 bytes) every time it hits the max it means after
    // every store only the most recent artifact should be left.
    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.of(9L), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashCache, resolver, ruleFinder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX));
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());

    Files.setAttribute(
        dirArtifactCache.getPathForRuleKey(ruleKeyX, Optional.empty()),
        "lastAccessTime",
        FileTime.fromMillis(0));
    Files.setAttribute(
        dirArtifactCache.getPathForRuleKey(ruleKeyX, Optional.of(".metadata")),
        "lastAccessTime",
        FileTime.fromMillis(0));

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY));
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());

    Files.setAttribute(
        dirArtifactCache.getPathForRuleKey(ruleKeyY, Optional.empty()),
        "lastAccessTime",
        FileTime.fromMillis(1000));
    Files.setAttribute(
        dirArtifactCache.getPathForRuleKey(ruleKeyY, Optional.of(".metadata")),
        "lastAccessTime",
        FileTime.fromMillis(1000));

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ));

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyY, LazyPath.ofInstance(fileY)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyZ, LazyPath.ofInstance(fileZ)))
            .getType());
  }

  @Test
  public void testCacheStoreMultipleKeys() throws IOException {
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX, "x".getBytes(UTF_8));

    RuleKey ruleKey1 = new RuleKey("aaaa");
    RuleKey ruleKey2 = new RuleKey("bbbb");

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey1, ruleKey2).build(),
        BorrowablePath.notBorrowablePath(fileX));

    // Test that artifact is available via both keys.
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKey1, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKey2, LazyPath.ofInstance(fileX)))
            .getType());
  }

  @Test
  public void testCacheStoreAndFetchMetadata() throws IOException {
    DirArtifactCache cache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    RuleKey ruleKey = new RuleKey("0000");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");

    // Create a dummy data file.
    Path data = Paths.get("data");
    projectFilesystem.touch(data);

    // Store the artifact with metadata then re-fetch.
    cache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
        BorrowablePath.notBorrowablePath(data));
    CacheResult result =
        Futures.getUnchecked(
            cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(Paths.get("out-data"))));

    // Verify that the metadata is correct.
    assertThat(result.getType(), Matchers.equalTo(CacheResultType.HIT));
    assertThat(result.getMetadata(), Matchers.equalTo(metadata));
    assertThat(
        result.getArtifactSizeBytes(), Matchers.equalTo(projectFilesystem.getFileSize(data)));

    cache.close();
  }

  @Test
  public void testFolderLevelsForRuleKeys() throws IOException {
    DirArtifactCache cache = newDirArtifactCache(Optional.empty(), CacheReadMode.READONLY);

    Path result = cache.getPathForRuleKey(new RuleKey("aabb0123123234e324"), Optional.empty());
    assertThat(result.endsWith(Paths.get("aa/bb/aabb0123123234e324")), Matchers.equalTo(true));

    result = cache.getPathForRuleKey(new RuleKey("aabb0123123234e324"), Optional.of(".ext"));
    assertThat(result.endsWith(Paths.get("aa/bb/aabb0123123234e324.ext")), Matchers.equalTo(true));

    cache.close();
  }

  private static class BuildRuleForTest extends FakeBuildRule {

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final SourcePath file;

    private BuildRuleForTest(Path file) {
      super(BuildTargetFactory.newInstance("//foo:" + file.getFileName()));
      // TODO(15468825) - PathSourcePath should be relative!11!!11!1!!!
      this.file = FakeSourcePath.of(new FakeProjectFilesystem(), file);
    }
  }

  private DirArtifactCache newDirArtifactCache(
      Optional<Long> maxCacheSizeBytes, CacheReadMode cacheReadMode) throws IOException {
    return new DirArtifactCache(
        "dir",
        projectFilesystem,
        cacheDir,
        cacheReadMode,
        maxCacheSizeBytes,
        MoreExecutors.newDirectExecutorService());
  }
}
