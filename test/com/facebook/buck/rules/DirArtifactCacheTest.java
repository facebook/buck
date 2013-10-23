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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.NullFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;

public class DirArtifactCacheTest {
  @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testCacheCreation() throws IOException {
    File cacheDir = tmpDir.newFolder();

    new DirArtifactCache(cacheDir, /* maxCacheSizeBytes */ Optional.of(0L));
  }

  @Test
  public void testCacheFetchMiss() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX,
        new NullFileHashCache()).build().getTotalRuleKey();

    assertEquals(CacheResult.MISS, dirArtifactCache.fetch(ruleKeyX, fileX));
  }

  @Test
  public void testCacheStoreAndFetchHit() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir,
        /* maxCacheSizeBytes */ Optional.<Long>absent());

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX, new NullFileHashCache()).build().getTotalRuleKey();

    dirArtifactCache.store(ruleKeyX, fileX);

    // Test that artifact overwrite works.
    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyX, fileX));
    assertEquals(inputRuleX, new InputRuleForTest(fileX));

    // Test that artifact creation works.
    assertTrue(fileX.delete());
    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyX, fileX));
    assertEquals(inputRuleX, new InputRuleForTest(fileX));
  }

  @Test
  public void testCacheStoreOverwrite() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir,
        /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX, new NullFileHashCache()).build().getTotalRuleKey();

    dirArtifactCache.store(ruleKeyX, fileX);
    dirArtifactCache.store(ruleKeyX, fileX); // Overwrite.

    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyX, fileX));
    assertEquals(inputRuleX, new InputRuleForTest(fileX));
  }

  @Test
  public void testCacheStoresAndFetchHits() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");
    File fileY = tmpDir.newFile("y");
    File fileZ = tmpDir.newFile("z");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir,
      /* maxCacheSizeBytes */ Optional.of(0L));

    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    InputRule inputRuleX = new InputRuleForTest(fileX);
    InputRule inputRuleY = new InputRuleForTest(fileY);
    InputRule inputRuleZ = new InputRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));

    NullFileHashCache hashCache = new NullFileHashCache();
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX, hashCache).build().getTotalRuleKey();
    RuleKey ruleKeyY = RuleKey.builder(inputRuleY, hashCache).build().getTotalRuleKey();
    RuleKey ruleKeyZ = RuleKey.builder(inputRuleZ, hashCache).build().getTotalRuleKey();

    assertEquals(CacheResult.MISS, dirArtifactCache.fetch(ruleKeyX, fileX));
    assertEquals(CacheResult.MISS, dirArtifactCache.fetch(ruleKeyY, fileY));
    assertEquals(CacheResult.MISS, dirArtifactCache.fetch(ruleKeyZ, fileZ));

    dirArtifactCache.store(ruleKeyX, fileX);
    dirArtifactCache.store(ruleKeyY, fileY);
    dirArtifactCache.store(ruleKeyZ, fileZ);

    assertTrue(fileX.delete());
    assertTrue(fileY.delete());
    assertTrue(fileZ.delete());

    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyX, fileX));
    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyY, fileY));
    assertEquals(CacheResult.DIR_HIT, dirArtifactCache.fetch(ruleKeyZ, fileZ));

    assertEquals(inputRuleX, new InputRuleForTest(fileX));
    assertEquals(inputRuleY, new InputRuleForTest(fileY));
    assertEquals(inputRuleZ, new InputRuleForTest(fileZ));

    assertEquals(3, cacheDir.listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(0, cacheDir.listFiles().length);
  }

  @Test
  public void testDeleteNothing() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = new File(cacheDir, "x");
    File fileY = new File(cacheDir, "y");
    File fileZ = new File(cacheDir, "z");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(tmpDir.getRoot(),
      /* maxCacheSizeBytes */ Optional.of(1024L));

    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    assertEquals(3, cacheDir.listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.listFiles().length);
  }

  @Test
  public void testDeleteNothingAbsentLimit() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = new File(cacheDir, "x");
    File fileY = new File(cacheDir, "y");
    File fileZ = new File(cacheDir, "z");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(tmpDir.getRoot(),
      /* maxCacheSizeBytes */ Optional.<Long>absent());

    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    assertEquals(3, cacheDir.listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(3, cacheDir.listFiles().length);
  }

  @Test
  public void testDeleteSome() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileW = new File(cacheDir, "w");
    File fileX = new File(cacheDir, "x");
    File fileY = new File(cacheDir, "y");
    File fileZ = new File(cacheDir, "z");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir,
      /* maxCacheSizeBytes */ Optional.of(2L));

    Files.write("w", fileW, Charsets.UTF_8);
    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    java.nio.file.Files.setAttribute(fileW.toPath(), "lastAccessTime", FileTime.fromMillis(9000));
    java.nio.file.Files.setAttribute(fileX.toPath(), "lastAccessTime", FileTime.fromMillis(0));
    java.nio.file.Files.setAttribute(fileY.toPath(), "lastAccessTime", FileTime.fromMillis(1000));
    java.nio.file.Files.setAttribute(fileZ.toPath(), "lastAccessTime", FileTime.fromMillis(2000));

    assertEquals(4, cacheDir.listFiles().length);

    dirArtifactCache.deleteOldFiles();

    assertEquals(ImmutableSet.of(fileZ, fileW), ImmutableSet.copyOf(cacheDir.listFiles()));
  }

  private static class InputRuleForTest extends InputRule {
    private InputRuleForTest(File file) {
      super(file, file.getPath());
    }
  }
}
