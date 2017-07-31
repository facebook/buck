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

package com.facebook.buck.util.autosparse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ManifestTrieTest {
  @Test
  public void emptyTrie() {
    ManifestTrie empty = new ManifestTrie();
    Path testPath = Paths.get("foo/bar/baz");

    assertTrue(empty.isEmpty());
    assertEquals(empty.size(), 0);
    assertFalse(empty.containsDirectory(testPath));
    assertFalse(empty.containsManifest(testPath));
    assertFalse(empty.containsLeafNodes(testPath));
    assertNull(empty.get(testPath));

    // trying to remove something doesn't blow up
    empty.remove(testPath);
    assertEquals(empty.size(), 0);
  }

  @Test
  public void addRetrieve() {
    ManifestTrie trie = new ManifestTrie();
    Path testPath1 = Paths.get("foo/bar/baz");
    ManifestInfo info1 = ManifestInfo.of("");
    Path testPath2 = Paths.get("foo/spam/eggs");

    trie.add(testPath1, info1);

    assertFalse(trie.isEmpty());
    assertEquals(trie.size(), 1);
    assertTrue(trie.containsDirectory(testPath1.getParent()));
    assertTrue(trie.containsLeafNodes(testPath1.getParent()));
    assertTrue(trie.containsManifest(testPath1));
    assertEquals(trie.get(testPath1), info1);
    assertNull(trie.get(testPath2));
  }

  @Test
  public void addRemove() {
    ManifestTrie trie = new ManifestTrie();
    Path testPath = Paths.get("foo/bar/baz");
    ManifestInfo info = ManifestInfo.of("");

    trie.add(testPath, info);
    trie.remove(testPath);

    assertTrue(trie.isEmpty());
    assertEquals(trie.size(), 0);
    assertFalse(trie.containsDirectory(testPath.getParent()));
    assertFalse(trie.containsLeafNodes(testPath.getParent()));
    assertFalse(trie.containsManifest(testPath));
    assertNull(trie.get(testPath));
  }

  @Test
  public void containsLeafNodes() {
    ManifestTrie trie = new ManifestTrie();

    Path testPathDeeper = Paths.get("foo/bar/deeper/path");
    ManifestInfo infoDeeper = ManifestInfo.of("");
    trie.add(testPathDeeper, infoDeeper);

    Path testPath1 = Paths.get("foo/bar/baz");
    ManifestInfo info1 = ManifestInfo.of("");
    trie.add(testPath1, info1);

    Path testPath2 = Paths.get("foo/bar/spam");
    ManifestInfo info2 = ManifestInfo.of("");
    trie.add(testPath2, info2);

    assertTrue(trie.containsLeafNodes(testPath1.getParent()));
    assertFalse(trie.containsLeafNodes(testPath1.getParent().getParent()));

    // Removing one of the leaves should still keep the contains test true:
    trie.remove(testPath2);
    assertTrue(trie.containsLeafNodes(testPath1.getParent()));

    // Removing the other means no leaves are left at this level
    trie.remove(testPath1);
    assertFalse(trie.containsLeafNodes(testPath1.getParent()));

    // But the directory still exists (there is a deeper path there)
    assertTrue(trie.containsDirectory(testPath1.getParent()));
  }

  @Test
  public void replaceExisting() {
    ManifestTrie trie = new ManifestTrie();
    Path testPath = Paths.get("foo/bar/baz");
    ManifestInfo info1 = ManifestInfo.of("");
    ManifestInfo info2 = ManifestInfo.of("");

    trie.add(testPath, info1);
    trie.add(testPath, info2);

    assertFalse(trie.isEmpty());
    assertEquals(trie.size(), 1);
    assertTrue(trie.containsDirectory(testPath.getParent()));
    assertTrue(trie.containsLeafNodes(testPath.getParent()));
    assertTrue(trie.containsManifest(testPath));
    assertEquals(trie.get(testPath), info2);
  }

  @Test
  public void multipleAddsRemove() {
    ManifestTrie trie = new ManifestTrie();

    Path testPath1 = Paths.get("foo/bar/baz");
    Path testPath2 = Paths.get("foo/ham/baz");
    ImmutableMap<Path, ManifestInfo> testPaths =
        ImmutableMap.of(
            testPath1,
            ManifestInfo.of(""),
            testPath2,
            ManifestInfo.of(""),
            Paths.get("ham/bar/foo"),
            ManifestInfo.of(""),
            Paths.get("ham/bar/spam"),
            ManifestInfo.of(""),
            Paths.get("foo/bar/spam"),
            ManifestInfo.of(""));
    testPaths.forEach((path, info) -> trie.add(path, info));
    trie.remove(testPath1);
    trie.remove(testPath2);

    assertFalse(trie.isEmpty());
    assertEquals(trie.size(), 3);
    assertFalse(trie.containsManifest(testPath1));
    assertFalse(trie.containsManifest(testPath2));

    // foo/bar/baz is gone, but foo/bar/spam is still present
    assertTrue(trie.containsDirectory(testPath1.getParent()));
    assertTrue(trie.containsLeafNodes(testPath1.getParent()));
    // No foo/ham paths remain
    assertFalse(trie.containsDirectory(testPath2.getParent()));
    assertFalse(trie.containsLeafNodes(testPath2.getParent()));

    // foo does exist still, but has no leaves
    assertTrue(trie.containsDirectory(testPath1.getParent().getParent()));
    assertFalse(trie.containsLeafNodes(testPath1.getParent().getParent()));
  }
}
