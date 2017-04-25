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

import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.WatchmanOverflowEvent;
import com.facebook.buck.util.WatchmanPathEvent;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class WatchedFileHashCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void whenNotifiedOfOverflowEventCacheIsCleared() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfCreateEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, path));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, path));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void whenNotifiedOfDeleteEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.DELETE, path));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }

  @Test
  public void directoryHashChangesWhenFileInsideDirectoryChanges()
      throws InterruptedException, IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    tmp.newFolder("foo", "bar");
    Path inputFile = tmp.newFile("foo/bar/baz");
    Files.write(inputFile, "Hello world".getBytes(Charsets.UTF_8));

    Path dir = Paths.get("foo/bar");
    HashCode dirHash = cache.get(dir);
    Files.write(inputFile, "Goodbye world".getBytes(Charsets.UTF_8));
    cache.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, dir.resolve("baz")));
    HashCode dirHash2 = cache.get(dir);
    assertNotEquals(dirHash, dirHash2);
  }

  @Test
  public void whenNotifiedOfChangeToSubPathThenDirCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path dir = Paths.get("foo/bar/baz");
    HashCodeAndFileType value =
        HashCodeAndFileType.ofDirectory(HashCode.fromInt(42), ImmutableSet.of());
    cache.loadingCache.put(dir, value);
    cache.sizeCache.put(dir, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, dir.resolve("blech")));
    assertFalse("Cache should not contain path", cache.willGet(dir));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(dir), nullValue());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);

    Path dir = filesystem.getPath("dir");
    filesystem.mkdirs(dir);
    Path child1 = dir.resolve("child1");
    filesystem.touch(child1);
    Path child2 = dir.resolve("child2");
    filesystem.touch(child2);

    cache.get(dir);
    assertTrue(cache.willGet(dir));
    assertTrue(cache.willGet(child1));
    assertTrue(cache.willGet(child2));

    // Trigger an event on the directory.
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, dir));

    assertNull(cache.loadingCache.getIfPresent(dir));
    assertNull(cache.loadingCache.getIfPresent(child1));
    assertNull(cache.loadingCache.getIfPresent(child2));
  }

  @Test
  public void whenNotifiedOfParentChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem);
    Path parent = filesystem.getPath("directory");
    Path path = parent.resolve("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    cache.sizeCache.put(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, parent));
    assertFalse("Cache should not contain path", cache.willGet(path));
    assertThat("Cache should not contain path", cache.sizeCache.getIfPresent(path), nullValue());
  }
}
