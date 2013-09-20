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

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class DirArtifactCacheTest {
  @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testCacheCreation() throws IOException {
    File cacheDir = tmpDir.newFolder();

    new DirArtifactCache(cacheDir);
  }

  @Test
  public void testCacheFetchMiss() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir);

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX).build().getTotalRuleKey();

    assertEquals(CacheResult.MISS, dirArtifactCache.fetch(ruleKeyX, fileX));
  }

  @Test
  public void testCacheStoreAndFetchHit() throws IOException {
    File cacheDir = tmpDir.newFolder();
    File fileX = tmpDir.newFile("x");

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir);

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX).build().getTotalRuleKey();

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

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir);

    Files.write("x", fileX, Charsets.UTF_8);
    InputRule inputRuleX = new InputRuleForTest(fileX);
    RuleKey ruleKeyX = RuleKey.builder(inputRuleX).build().getTotalRuleKey();

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

    DirArtifactCache dirArtifactCache = new DirArtifactCache(cacheDir);

    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    InputRule inputRuleX = new InputRuleForTest(fileX);
    InputRule inputRuleY = new InputRuleForTest(fileY);
    InputRule inputRuleZ = new InputRuleForTest(fileZ);
    assertFalse(inputRuleX.equals(inputRuleY));
    assertFalse(inputRuleX.equals(inputRuleZ));
    assertFalse(inputRuleY.equals(inputRuleZ));

    RuleKey ruleKeyX = RuleKey.builder(inputRuleX).build().getTotalRuleKey();
    RuleKey ruleKeyY = RuleKey.builder(inputRuleY).build().getTotalRuleKey();
    RuleKey ruleKeyZ = RuleKey.builder(inputRuleZ).build().getTotalRuleKey();

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
  }

  private static class InputRuleForTest extends InputRule {
    private InputRuleForTest(File file) {
      super(file, file.getPath());
    }
  }
}
