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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;

public class DirArtifactCacheTest {
  @Rule
  public TemporaryPaths tmpDir = new TemporaryPaths();

  private FileHashCache fileHashCache = new NullFileHashCache();

  private DirArtifactCache dirArtifactCache;

  @After
  public void tearDown() {
    if (dirArtifactCache != null) {
      dirArtifactCache.close();
    }
  }

  @Test
  public void testCacheCreation() throws IOException {
    Path cacheDir = tmpDir.newFolder();

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(0L));
  }

  @Test
  public void testCacheFetchMiss() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        inputRuleX)));
    RuleKey ruleKeyX = new DefaultRuleKeyBuilderFactory(fileHashCache, resolver)
        .newInstance(inputRuleX)
        .build();

    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
  }

  @Test
  public void testCacheStoreAndFetchHit() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.<Long>absent());

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        inputRuleX)));
    RuleKey ruleKeyX = new DefaultRuleKeyBuilderFactory(fileHashCache, resolver)
        .newInstance(inputRuleX)
        .build();

    dirArtifactCache.store(ImmutableSet.of(ruleKeyX), ImmutableMap.<String, String>of(), fileX);

    // Test that artifact overwrite works.
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));

    // Test that artifact creation works.
    Files.delete(fileX);
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
  }

  @Test
  public void testCacheStoreOverwrite() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write(fileX, "x".getBytes(UTF_8));
    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        inputRuleX)));
    RuleKey ruleKeyX = new DefaultRuleKeyBuilderFactory(fileHashCache, resolver)
        .newInstance(inputRuleX)
        .build();

    dirArtifactCache.store(ImmutableSet.of(ruleKeyX), ImmutableMap.<String, String>of(), fileX);

    // Overwrite.
    dirArtifactCache.store(ImmutableSet.of(ruleKeyX), ImmutableMap.<String, String>of(), fileX);

    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
  }

  @Test
  public void testCacheStoresAndFetchHits() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "x".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        inputRuleX,
        inputRuleY,
        inputRuleZ)));

    DefaultRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver);

    RuleKey ruleKeyX = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleX)
        .build();
    RuleKey ruleKeyY = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleY)
        .build();
    RuleKey ruleKeyZ = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleZ)
        .build();

    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyY, fileY).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyZ, fileZ).getType());

    dirArtifactCache.store(ImmutableSet.of(ruleKeyX), ImmutableMap.<String, String>of(), fileX);
    dirArtifactCache.store(ImmutableSet.of(ruleKeyY), ImmutableMap.<String, String>of(), fileY);
    dirArtifactCache.store(ImmutableSet.of(ruleKeyZ), ImmutableMap.<String, String>of(), fileZ);

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyY, fileY).getType());
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKeyZ, fileZ).getType());

    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
    assertEquals(inputRuleY, new BuildRuleForTest(fileY));
    assertEquals(inputRuleZ, new BuildRuleForTest(fileZ));

    assertEquals(6, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(0, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testNoStoreMisses() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");
    Path fileY = tmpDir.newFile("y");
    Path fileZ = tmpDir.newFile("z");

    fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fileX, HashCode.fromInt(0),
                fileY, HashCode.fromInt(1),
                fileZ, HashCode.fromInt(2)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ false,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    BuildRule inputRuleX = new BuildRuleForTest(fileX);
    BuildRule inputRuleY = new BuildRuleForTest(fileY);
    BuildRule inputRuleZ = new BuildRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        inputRuleX,
        inputRuleY,
        inputRuleZ)));

    DefaultRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver);

    RuleKey ruleKeyX = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleX)
        .build();
    RuleKey ruleKeyY = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleY)
        .build();
    RuleKey ruleKeyZ = fakeRuleKeyBuilderFactory
        .newInstance(inputRuleZ)
        .build();

    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyY, fileY).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyZ, fileZ).getType());

    dirArtifactCache.store(ImmutableSet.of(ruleKeyX), ImmutableMap.<String, String>of(), fileX);
    dirArtifactCache.store(ImmutableSet.of(ruleKeyY), ImmutableMap.<String, String>of(), fileY);
    dirArtifactCache.store(ImmutableSet.of(ruleKeyZ), ImmutableMap.<String, String>of(), fileZ);

    Files.delete(fileX);
    Files.delete(fileY);
    Files.delete(fileZ);

    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyX, fileX).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyY, fileY).getType());
    assertEquals(CacheResult.Type.MISS, dirArtifactCache.fetch(ruleKeyZ, fileZ).getType());

    assertEquals(inputRuleX, new BuildRuleForTest(fileX));
    assertEquals(inputRuleY, new BuildRuleForTest(fileY));
    assertEquals(inputRuleZ, new BuildRuleForTest(fileZ));

    assertEquals(0, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteNothing() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = cacheDir.resolve("x");
    Path fileY = cacheDir.resolve("y");
    Path fileZ = cacheDir.resolve("z");

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(tmpDir.getRoot()),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(1024L));

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteNothingAbsentLimit() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = cacheDir.resolve("x");
    Path fileY = cacheDir.resolve("y");
    Path fileZ = cacheDir.resolve("z");

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(tmpDir.getRoot()),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.<Long>absent());

    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    assertEquals(3, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.toFile().listFiles().length);
  }

  @Test
  public void testDeleteSome() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileW = cacheDir.resolve("w");
    Path fileX = cacheDir.resolve("x");
    Path fileY = cacheDir.resolve("y");
    Path fileZ = cacheDir.resolve("z");

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.of(2L));

    Files.write(fileW, "w".getBytes(UTF_8));
    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    Files.setAttribute(fileW, "lastAccessTime", FileTime.fromMillis(9000));
    Files.setAttribute(fileX, "lastAccessTime", FileTime.fromMillis(0));
    Files.setAttribute(fileY, "lastAccessTime", FileTime.fromMillis(1000));
    Files.setAttribute(fileZ, "lastAccessTime", FileTime.fromMillis(2000));

    assertEquals(4, cacheDir.toFile().listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(
        ImmutableSet.of(fileZ, fileW),
        FluentIterable.of(cacheDir.toFile().listFiles()).transform(
            new Function<File, Path>() {
              @Override
              public Path apply(File input) {
                return input.toPath();
              }
            })
            .toSet());
  }

  @Test
  public void testCacheStoreMultipleKeys() throws IOException {
    Path cacheDir = tmpDir.newFolder();
    Path fileX = tmpDir.newFile("x");

    fileHashCache = new FakeFileHashCache(ImmutableMap.of(fileX, HashCode.fromInt(0)));

    dirArtifactCache = new DirArtifactCache(
        "dir",
        new ProjectFilesystem(cacheDir),
        Paths.get("."),
        /* doStore */ true,
        /* maxCacheSizeBytes */ Optional.<Long>absent());

    Files.write(fileX, "x".getBytes(UTF_8));

    RuleKey ruleKey1 = new RuleKey("aaaa");
    RuleKey ruleKey2 = new RuleKey("bbbb");

    dirArtifactCache.store(
        ImmutableSet.of(ruleKey1, ruleKey2),
        ImmutableMap.<String, String>of(),
        fileX);

    // Test that artifact is available via both keys.
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKey1, fileX).getType());
    assertEquals(CacheResult.Type.HIT, dirArtifactCache.fetch(ruleKey2, fileX).getType());
  }

  @Test
  public void testCacheStoreAndFetchMetadata() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DirArtifactCache cache =
        new DirArtifactCache(
            "dir",
            filesystem,
            Paths.get("cache"),
            /* doStore */ true,
            /* maxCacheSizeBytes */ Optional.<Long>absent());

    RuleKey ruleKey = new RuleKey("0000");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");

    // Create a dummy data file.
    Path data = Paths.get("data");
    filesystem.touch(data);

    // Store the artifact with metadata then re-fetch.
    cache.store(ImmutableSet.of(ruleKey), metadata, data);
    CacheResult result = cache.fetch(ruleKey, Paths.get("out-data"));

    // Verify that the metadata is correct.
    assertThat(
        result.getType(),
        Matchers.equalTo(CacheResult.Type.HIT));
    assertThat(
        result.getMetadata(),
        Matchers.equalTo(metadata));

    cache.close();
  }

  private static class BuildRuleForTest extends FakeBuildRule {

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final Path file;

    private BuildRuleForTest(Path file) {
      super(
          BuildTarget.builder("//foo", file.getFileName().toString()).build(),
          new SourcePathResolver(new BuildRuleResolver()));
      this.file = file;
    }
  }
}
