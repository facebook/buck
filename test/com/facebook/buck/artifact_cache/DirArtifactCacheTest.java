/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
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
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.DataOutputStream;
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

  private FileHashLoader fileHashLoader = new DummyFileHashCache();

  private DirArtifactCache dirArtifactCache;
  private AbsPath cacheDir;
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
    AbsPath fileX = tmpDir.newFile("x");

    fileHashLoader = new FakeFileHashCache(ImmutableMap.of(fileX.getPath(), HashCode.fromInt(0)));

    Optional<Long> maxCacheSizeBytes = Optional.of(0L);
    dirArtifactCache = newDirArtifactCache(maxCacheSizeBytes, CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX.getPath());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder).build(inputRuleX);

    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
  }

  @Test
  public void testCacheStoreAndFetchHit() throws IOException {
    AbsPath fileX = tmpDir.newFile("x");

    fileHashLoader = new FakeFileHashCache(ImmutableMap.of(fileX.getPath(), HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX.getPath());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder).build(inputRuleX);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX.getPath()));

    // Test that artifact overwrite works.
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX.getPath()));

    // Test that artifact creation works.
    Files.delete(fileX.getPath());
    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX.getPath()));
  }

  @Test
  public void testCacheStoreOverwrite() throws IOException {
    AbsPath fileX = tmpDir.newFile("x");

    fileHashLoader = new FakeFileHashCache(ImmutableMap.of(fileX.getPath(), HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX.getPath());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    RuleKey ruleKeyX =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder).build(inputRuleX);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX.getPath()));

    // Overwrite.
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX.getPath()));

    assertEquals(
        CacheResultType.HIT,
        Futures.getUnchecked(
                dirArtifactCache.fetchAsync(null, ruleKeyX, LazyPath.ofInstance(fileX)))
            .getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX.getPath()));
  }

  @Test
  public void testCacheStoresAndFetchHits() throws IOException {
    AbsPath fileX = tmpDir.newFile("x");
    AbsPath fileY = tmpDir.newFile("y");
    AbsPath fileZ = tmpDir.newFile("z");

    fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX.getPath(), HashCode.fromInt(0),
                fileY.getPath(), HashCode.fromInt(1),
                fileZ.getPath(), HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertNotEquals(inputRuleX, inputRuleY);
    assertNotEquals(inputRuleX, inputRuleZ);
    assertNotEquals(inputRuleY, inputRuleZ);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder);

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
        BorrowablePath.notBorrowablePath(fileX.getPath()));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY.getPath()));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ.getPath()));

    Files.delete(fileX.getPath());
    Files.delete(fileY.getPath());
    Files.delete(fileZ.getPath());

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
    AbsPath fileX = tmpDir.newFile("x");
    AbsPath fileY = tmpDir.newFile("y");
    AbsPath fileZ = tmpDir.newFile("z");

    fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX.getPath(), HashCode.fromInt(0),
                fileY.getPath(), HashCode.fromInt(1),
                fileZ.getPath(), HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertNotEquals(inputRuleX, inputRuleY);
    assertNotEquals(inputRuleX, inputRuleZ);
    assertNotEquals(inputRuleY, inputRuleZ);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder);

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
                BorrowablePath.notBorrowablePath(fileX.getPath())),
            new Pair<>(
                ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
                BorrowablePath.notBorrowablePath(fileY.getPath())),
            new Pair<>(
                ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
                BorrowablePath.notBorrowablePath(fileZ.getPath()))));

    Files.delete(fileX.getPath());
    Files.delete(fileY.getPath());
    Files.delete(fileZ.getPath());

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
  public void testCacheStoresAndBorrowsPaths() throws IOException {
    AbsPath fileX = tmpDir.newFile("x");
    AbsPath fileY = tmpDir.newFile("y");

    fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX.getPath(), HashCode.fromInt(0),
                fileY.getPath(), HashCode.fromInt(1)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    assertNotEquals(inputRuleX, inputRuleY);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.borrowablePath(fileX.getPath()));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY.getPath()));

    assertThat(Files.exists(fileX.getPath()), Matchers.is(false));
    assertThat(Files.exists(fileY.getPath()), Matchers.is(true));
  }

  @Test
  public void testNoStoreMisses() throws IOException {
    AbsPath fileX = tmpDir.newFile("x");
    AbsPath fileY = tmpDir.newFile("y");
    AbsPath fileZ = tmpDir.newFile("z");

    fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX.getPath(),
                HashCode.fromInt(0),
                fileY.getPath(),
                HashCode.fromInt(1),
                fileZ.getPath(),
                HashCode.fromInt(2)));

    dirArtifactCache = newDirArtifactCache(Optional.of(0L), CacheReadMode.READONLY);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "z".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertNotEquals(inputRuleX, inputRuleY);
    assertNotEquals(inputRuleX, inputRuleZ);
    assertNotEquals(inputRuleY, inputRuleZ);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder);

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
        BorrowablePath.notBorrowablePath(fileX.getPath()));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyY).build(),
        BorrowablePath.notBorrowablePath(fileY.getPath()));
    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyZ).build(),
        BorrowablePath.notBorrowablePath(fileZ.getPath()));

    Files.delete(fileX.getPath());
    Files.delete(fileY.getPath());
    Files.delete(fileZ.getPath());

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
    AbsPath fileX = cacheDir.resolve("x");
    AbsPath fileY = cacheDir.resolve("y");
    AbsPath fileZ = cacheDir.resolve("z");

    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.of(1024L), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteNothingAbsentLimit() throws IOException {
    AbsPath fileX = cacheDir.resolve("x");
    AbsPath fileY = cacheDir.resolve("y");
    AbsPath fileZ = cacheDir.resolve("z");

    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteSome() throws IOException {
    AbsPath fileW = cacheDir.resolve("11").resolve("11").resolve("w");
    AbsPath fileX = cacheDir.resolve("22").resolve("22").resolve("x");
    AbsPath fileY = cacheDir.resolve("33").resolve("33").resolve("y");
    AbsPath fileZ = cacheDir.resolve("44").resolve("44").resolve("z");

    Files.createDirectories(fileW.getParent().getPath());
    Files.createDirectories(fileX.getParent().getPath());
    Files.createDirectories(fileY.getParent().getPath());
    Files.createDirectories(fileZ.getParent().getPath());

    dirArtifactCache = newDirArtifactCache(Optional.of(3L), CacheReadMode.READWRITE);

    Files.write(fileW.getPath(), "w".getBytes(UTF_8));
    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "z".getBytes(UTF_8));

    Files.setAttribute(fileW.getPath(), "lastAccessTime", FileTime.fromMillis(9000));
    Files.setAttribute(fileX.getPath(), "lastAccessTime", FileTime.fromMillis(0));
    Files.setAttribute(fileY.getPath(), "lastAccessTime", FileTime.fromMillis(1000));
    Files.setAttribute(fileZ.getPath(), "lastAccessTime", FileTime.fromMillis(2000));

    // On some filesystems, setting creationTime silently fails. So don't test that here.

    assertEquals(4, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    List<Path> filesInCache = dirArtifactCache.getAllFilesInCache();
    assertEquals(
        ImmutableSet.of(fileZ.getPath(), fileW.getPath()), ImmutableSet.copyOf(filesInCache));
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
    AbsPath fileX = tmpDir.newFile("x");
    AbsPath fileY = tmpDir.newFile("y");
    AbsPath fileZ = tmpDir.newFile("z");

    fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX.getPath(), HashCode.fromInt(0),
                fileY.getPath(), HashCode.fromInt(1),
                fileZ.getPath(), HashCode.fromInt(2)));

    // The reason max size is 9 bytes is because a 1-byte entry actually takes 6 bytes to store.
    // If the cache trims the size down to 2/3 (6 bytes) every time it hits the max it means after
    // every store only the most recent artifact should be left.
    dirArtifactCache =
        newDirArtifactCache(/* maxCacheSizeBytes */ Optional.of(9L), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));
    Files.write(fileY.getPath(), "y".getBytes(UTF_8));
    Files.write(fileZ.getPath(), "z".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(inputRuleX);
    graphBuilder.addToIndex(inputRuleY);
    graphBuilder.addToIndex(inputRuleZ);

    DefaultRuleKeyFactory fakeRuleKeyFactory =
        new TestDefaultRuleKeyFactory(fileHashLoader, graphBuilder);

    RuleKey ruleKeyX = fakeRuleKeyFactory.build(inputRuleX);
    RuleKey ruleKeyY = fakeRuleKeyFactory.build(inputRuleY);
    RuleKey ruleKeyZ = fakeRuleKeyFactory.build(inputRuleZ);

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyX).build(),
        BorrowablePath.notBorrowablePath(fileX.getPath()));
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
        BorrowablePath.notBorrowablePath(fileY.getPath()));
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
        BorrowablePath.notBorrowablePath(fileZ.getPath()));

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
    AbsPath fileX = tmpDir.newFile("x");

    fileHashLoader = new FakeFileHashCache(ImmutableMap.of(fileX.getPath(), HashCode.fromInt(0)));

    dirArtifactCache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    Files.write(fileX.getPath(), "x".getBytes(UTF_8));

    RuleKey ruleKey1 = new RuleKey("aaaa");
    RuleKey ruleKey2 = new RuleKey("bbbb");

    dirArtifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey1, ruleKey2).build(),
        BorrowablePath.notBorrowablePath(fileX.getPath()));

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
  public void testCacheStoreCorruptedMetadataAndFetchMiss() throws IOException {
    DirArtifactCache cache = newDirArtifactCache(Optional.empty(), CacheReadMode.READWRITE);

    RuleKey ruleKey = new RuleKey("0000");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");

    // Create a dummy data file.
    Path data = Paths.get("data");
    projectFilesystem.touch(data);

    // Store the artifact with metadata
    cache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
        BorrowablePath.notBorrowablePath(data));

    // Corrupt the metadata
    Path metadataPath = cache.getPathForRuleKey(ruleKey, Optional.of(".metadata"));
    try (DataOutputStream out =
        new DataOutputStream(projectFilesystem.newFileOutputStream(metadataPath))) {
      out.writeInt(1);
      out.flush();
    }

    CacheResult result =
        Futures.getUnchecked(
            cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(Paths.get("out-data"))));

    // Verify that the metadata is correct.
    assertThat(result.getType(), Matchers.equalTo(CacheResultType.ERROR));

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

    private BuildRuleForTest(AbsPath file) {
      this(file.getPath());
    }
  }

  private DirArtifactCache newDirArtifactCache(
      Optional<Long> maxCacheSizeBytes, CacheReadMode cacheReadMode) throws IOException {
    return new DirArtifactCache(
        "dir",
        projectFilesystem,
        cacheDir.getPath(),
        cacheReadMode,
        maxCacheSizeBytes,
        MoreExecutors.newDirectExecutorService());
  }
}
