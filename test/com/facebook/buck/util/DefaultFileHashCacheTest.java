/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util;

import static com.facebook.buck.testutil.WatchEvents.createOverflowEvent;
import static com.facebook.buck.testutil.WatchEvents.createPathEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;

public class DefaultFileHashCacheTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void whenPathIsPutCacheContainsPath() {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = new File("SomeClass.java").toPath();
    cache.loadingCache.put(path, HashCode.fromInt(42));
    assertTrue("Cache should contain path", cache.contains(path));
  }

  @Test
  public void whenPathIsPutPathGetReturnsHash() {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = new File("SomeClass.java").toPath();
    HashCode hash = HashCode.fromInt(42);
    cache.loadingCache.put(path, hash);
    assertEquals("Cache should contain hash", hash, cache.get(path));
  }

  @Test
  public void whenNotifiedOfOverflowEventCacheIsCleared() throws IOException {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = new File("SomeClass.java").toPath();
    HashCode hash = HashCode.fromInt(42);
    cache.loadingCache.put(path, hash);
    cache.onFileSystemChange(createOverflowEvent());
    assertFalse("Cache should not contain path", cache.contains(path));
  }

  @Test
  public void whenNotifiedOfCreateEventCacheEntryIsRemoved() throws IOException {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = Paths.get("SomeClass.java");
    HashCode hash = HashCode.fromInt(42);
    cache.loadingCache.put(path, hash);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_CREATE));
    assertFalse("Cache should not contain path", cache.contains(path));
  }

  @Test
  public void whenNotifiedOfChangeEventCacheEntryIsRemoved() throws IOException {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = Paths.get("SomeClass.java");
    HashCode hash = HashCode.fromInt(42);
    cache.loadingCache.put(path, hash);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_MODIFY));
    assertFalse("Cache should not contain path", cache.contains(path));
  }

  @Test
  public void whenNotifiedOfDeleteEventCacheEntryIsRemoved() throws IOException {
    DefaultFileHashCache cache =
        new DefaultFileHashCache(new ProjectFilesystem(new File(".")));
    Path path = Paths.get("SomeClass.java");
    HashCode hash = HashCode.fromInt(42);
    cache.loadingCache.put(path, hash);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_DELETE));
    assertFalse("Cache should not contain path", cache.contains(path));
  }

  @Test
  public void whenPathIsIgnoredThenCacheDoesNotContainPath() throws IOException {
    String ignoredFolder = "buck-out";
    String ignoredFile = ignoredFolder + "/SomeClass.java";
    DefaultFileHashCache cache =
        new DefaultFileHashCache(
            new ProjectFilesystem(
                tmp.getRoot().toPath(),
                ImmutableSet.of(tmp.newFolder(ignoredFolder).toPath())));
    File inputFile = tmp.newFile(ignoredFile);
    Files.write("class SomeClass {}".getBytes(Charsets.US_ASCII), inputFile);
    cache.get(Paths.get(ignoredFile));
    assertFalse("Cache should not contain path.", cache.contains(inputFile.toPath()));
  }
}
