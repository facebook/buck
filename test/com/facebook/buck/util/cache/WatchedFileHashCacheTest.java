/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.cache;

import static com.facebook.buck.testutil.WatchEventsForTests.createOverflowEvent;
import static com.facebook.buck.testutil.WatchEventsForTests.createPathEvent;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;

public class WatchedFileHashCacheTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void whenNotifiedOfOverflowEventCacheIsCleared() throws IOException {
    WatchedFileHashCache cache = new WatchedFileHashCache(
        new FakeProjectFilesystem());
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(createOverflowEvent());
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfCreateEventCacheEntryIsRemoved() throws IOException {
    WatchedFileHashCache cache = new WatchedFileHashCache(
        new FakeProjectFilesystem());
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_CREATE));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfChangeEventCacheEntryIsRemoved() throws IOException {
    WatchedFileHashCache cache =
        new WatchedFileHashCache(new FakeProjectFilesystem());
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_MODIFY));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfDeleteEventCacheEntryIsRemoved() throws IOException {
    WatchedFileHashCache cache =
        new WatchedFileHashCache(new FakeProjectFilesystem());
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(createPathEvent(path, StandardWatchEventKinds.ENTRY_DELETE));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void directoryHashChangesWhenFileInsideDirectoryChanges() throws IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    tmp.newFolder("foo", "bar");
    Path inputFile = tmp.newFile("foo/bar/baz");
    Files.write(inputFile, "Hello world".getBytes(Charsets.UTF_8));

    Path dir = Paths.get("foo/bar");
    HashCode dirHash = cache.get(filesystem.resolve(dir));
    Files.write(inputFile, "Goodbye world".getBytes(Charsets.UTF_8));
    cache.onFileSystemChange(
        createPathEvent(
            dir.resolve("baz"),
            StandardWatchEventKinds.ENTRY_MODIFY));
    HashCode dirHash2 = cache.get(filesystem.resolve(dir));
    assertNotEquals(dirHash, dirHash2);
  }

  @Test
  public void whenNotifiedOfChangeToSubPathThenDirCacheEntryIsRemoved() throws IOException {
    WatchedFileHashCache cache =
        new WatchedFileHashCache(new FakeProjectFilesystem());
    Path dir = Paths.get("foo/bar/baz");
    HashCodeAndFileType value =
        HashCodeAndFileType.ofDirectory(HashCode.fromInt(42), ImmutableSet.of());
    cache.loadingCache.put(dir, value);
    cache.sizeCache.put(dir, 1234L);
    cache.onFileSystemChange(
        createPathEvent(
            dir.resolve("blech"),
            StandardWatchEventKinds.ENTRY_CREATE));
    assertFalse("Cache should not contain path", cache.willGet(dir));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(dir), nullValue());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);

    Path dir = filesystem.getRootPath().getFileSystem().getPath("dir");
    filesystem.mkdirs(dir);
    Path child1 = dir.resolve("child1");
    filesystem.touch(child1);
    Path child2 = dir.resolve("child2");
    filesystem.touch(child2);

    cache.get(filesystem.resolve(dir));
    assertTrue(cache.willGet(filesystem.resolve(dir)));
    assertTrue(cache.willGet(filesystem.resolve(child1)));
    assertTrue(cache.willGet(filesystem.resolve(child2)));

    // Trigger an event on the directory.
    cache.onFileSystemChange(createPathEvent(dir, StandardWatchEventKinds.ENTRY_MODIFY));

    assertNull(cache.loadingCache.getIfPresent(dir));
    assertNull(cache.loadingCache.getIfPresent(child1));
    assertNull(cache.loadingCache.getIfPresent(child2));
  }

  @Test
  public void whenNotifiedOfParentChangeEventCacheEntryIsRemoved() throws IOException {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
      Path parent = filesystem.getRootPath().getFileSystem().getPath("directory");
      Path path = parent.resolve("SomeClass.java");
      HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
      cache.loadingCache.put(path, value);
      cache.sizeCache.put(path, 1234L);
      cache.onFileSystemChange(createPathEvent(parent, StandardWatchEventKinds.ENTRY_MODIFY));
      assertFalse("Cache should not contain path", cache.willGet(path));
      assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
    }

}
