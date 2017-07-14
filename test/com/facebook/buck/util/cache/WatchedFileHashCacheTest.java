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
  private static final FileHashCacheMode FILE_HASH_CACHE_MODE = FileHashCacheMode.DEFAULT;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void whenNotifiedOfOverflowEventCacheIsCleared() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfCreateEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, path));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, path));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfDeleteEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path path = Paths.get("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.DELETE, path));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void directoryHashChangesWhenFileInsideDirectoryChanges()
      throws InterruptedException, IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
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
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path dir = Paths.get("foo/bar/baz");
    HashCodeAndFileType value =
        HashCodeAndFileType.ofDirectory(HashCode.fromInt(42), ImmutableSet.of());
    cache.fileHashCacheEngine.put(dir, value);
    cache.fileHashCacheEngine.putSize(dir, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, dir.resolve("blech")));
    assertFalse("Cache should not contain path", cache.getIfPresent(dir).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(dir),
        nullValue());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);

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

    assertFalse(cache.getIfPresent(dir).isPresent());
    assertFalse(cache.getIfPresent(child1).isPresent());
    assertFalse(cache.getIfPresent(child2).isPresent());
  }

  @Test
  public void whenNotifiedOfParentChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    Path parent = filesystem.getPath("directory");
    Path path = parent.resolve("SomeClass.java");
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), WatchmanPathEvent.Kind.MODIFY, parent));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void thatWillGetIsCorrect() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    Path buckOut = filesystem.getBuckPaths().getBuckOut();
    filesystem.mkdirs(buckOut);
    Path buckOutFile = buckOut.resolve("file.txt");
    Path otherFile = Paths.get("file.txt");
    filesystem.writeContentsToPath("data", buckOutFile);
    filesystem.writeContentsToPath("other data", otherFile);
    WatchedFileHashCache cache = new WatchedFileHashCache(filesystem, FILE_HASH_CACHE_MODE);
    assertFalse(cache.willGet(filesystem.getPath("buck-out/file.txt")));
    assertTrue(cache.willGet(filesystem.getPath("file.txt")));
  }
}
