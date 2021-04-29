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

package com.facebook.buck.core.files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class DirectoryListCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void canReadAndWrite() {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(Paths.get("folder")),
            ImmutableSortedSet.of(Paths.get("symlink"))));

    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("")));
    assertTrue(dlist.isPresent());
    assertTrue(dlist.get().getDirectories().contains(Paths.get("folder")));
    assertTrue(dlist.get().getFiles().contains(Paths.get("file")));
    assertTrue(dlist.get().getSymlinks().contains(Paths.get("symlink")));

    Optional<DirectoryList> dlistEmpty =
        cache.get(ImmutableDirectoryListKey.of(Paths.get("nonexisting")));
    assertFalse(dlistEmpty.isPresent());
  }

  private Object getInvalidateParameters() {
    return new Object[] {Kind.CREATE, Kind.DELETE};
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeThenInvalidate(Kind kind) {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    // should not invalidate
    WatchmanPathEvent event =
        WatchmanPathEvent.of(tmp.getRoot(), kind, ForwardRelPath.of("dir1/file"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertTrue(dlist.isPresent());

    // should invalidate
    event = WatchmanPathEvent.of(tmp.getRoot(), kind, ForwardRelPath.of("dir/file1"));
    cache.getInvalidator().onFileSystemChange(event);

    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertFalse(dlist.isPresent());
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeAtRootThenInvalidate(Kind kind) {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    WatchmanPathEvent event = WatchmanPathEvent.of(tmp.getRoot(), kind, ForwardRelPath.of("file1"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("")));
    assertFalse(dlist.isPresent());
  }

  @Test
  public void whenFileListNotChangeThenNotInvalidate() {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    // should not invalidate
    WatchmanPathEvent event =
        WatchmanPathEvent.of(tmp.getRoot(), Kind.MODIFY, ForwardRelPath.of("dir/file"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertTrue(dlist.isPresent());
  }

  @Test
  public void whenOverflowThenInvalidateAll() {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir1")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(Paths.get("dir1/subdir1")),
            ImmutableSortedSet.of()));

    // should not invalidate
    WatchmanOverflowEvent event = WatchmanOverflowEvent.of(tmp.getRoot(), "Test");
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertFalse(dlist.isPresent());

    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir1")));
    assertFalse(dlist.isPresent());
  }

  @Test
  public void whenFolderIsDeletedThenInvalidateParent() throws Exception {
    AbsPath root = tmp.getRoot();
    DirectoryListCache cache = DirectoryListCache.of(root);
    AbsPath dir1 = root.resolve("dir1");
    Files.createDirectory(dir1.getPath());
    Files.createFile(dir1.resolve("file1").getPath());
    AbsPath dir2 = dir1.resolve("dir2");
    Files.createDirectory(dir2.getPath());
    Files.createFile(dir2.resolve("file2").getPath());

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir1")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("dir1/file1")),
            ImmutableSortedSet.of(Paths.get("dir1/dir2")),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir1/dir2")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("dir1/dir2/file2")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    MoreFiles.deleteRecursively(dir2.getPath(), RecursiveDeleteOption.ALLOW_INSECURE);

    WatchmanPathEvent event =
        WatchmanPathEvent.of(root, Kind.DELETE, ForwardRelPath.of("dir1/dir2/file2"));
    FileHashCacheEvent.InvalidationStarted started = FileHashCacheEvent.invalidationStarted();
    cache.getInvalidator().onInvalidationStart(started);
    cache.getInvalidator().onFileSystemChange(event);
    cache.getInvalidator().onInvalidationFinish(FileHashCacheEvent.invalidationFinished(started));

    // should invalidate both folder and parent folder
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir1/dir2")));
    assertFalse(dlist.isPresent());

    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir1")));
    assertFalse(dlist.isPresent());
  }

  @Test
  public void whenRootFolderIsDeletedThenInvalidateAll() throws Exception {
    AbsPath root = tmp.getRoot().resolve("root");
    Files.createDirectory(root.getPath());

    DirectoryListCache cache = DirectoryListCache.of(root);
    AbsPath dir = root.resolve("dir");
    Files.createDirectory(dir.getPath());
    Files.createFile(dir.resolve("file").getPath());

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(Paths.get("dir")),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("dir/file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    MoreFiles.deleteRecursively(root.getPath(), RecursiveDeleteOption.ALLOW_INSECURE);

    WatchmanPathEvent event =
        WatchmanPathEvent.of(root, Kind.DELETE, ForwardRelPath.of("dir/file"));
    FileHashCacheEvent.InvalidationStarted started = FileHashCacheEvent.invalidationStarted();
    cache.getInvalidator().onInvalidationStart(started);
    cache.getInvalidator().onFileSystemChange(event);
    cache.getInvalidator().onInvalidationFinish(FileHashCacheEvent.invalidationFinished(started));

    // should invalidate root properly
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("")));
    assertFalse(dlist.isPresent());

    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertFalse(dlist.isPresent());
  }

  @Test
  public void whenNestedFolderIsCreatedInvalidateParents() throws Exception {
    AbsPath root = tmp.getRoot().resolve("root");
    DirectoryListCache cache = DirectoryListCache.of(root);

    // directory structure:
    //  root
    //   \
    //    + dir1
    //    |
    //    + dir2
    //    \
    //     foo
    //     \
    //      bar
    //      \
    //       baz.txt

    Files.createDirectory(root.getPath());
    Files.createDirectory(root.resolve("dir1").getPath());
    Files.createDirectory(root.resolve("dir2").getPath());
    Files.createDirectory(root.resolve("dir2").resolve("foo").getPath());
    Files.createDirectory(root.resolve("dir2").resolve("foo").resolve("bar").getPath());
    Files.createFile(
        root.resolve("dir2").resolve("foo").resolve("bar").resolve("baz.txt").getPath());

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(Paths.get("dir1"), Paths.get("dir2")),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir1")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(), ImmutableSortedSet.of(), ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir2")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(Paths.get("dir2/foo")),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir2/foo")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(Paths.get("dir2/foo/bar")),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir2/foo/bar")),
        ImmutableDirectoryList.ofImpl(
            ImmutableSortedSet.of(Paths.get("dir2/foo/bar/baz.txt")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    // Copy the contents of bar into quux, a new child directory of foo.
    Files.createDirectory(root.resolve("dir2").resolve("foo").resolve("quux").getPath());
    Files.createFile(
        root.resolve("dir2").resolve("foo").resolve("quux").resolve("baz.txt").getPath());

    // Watchman fires a Create event for baz.txt
    WatchmanPathEvent event =
        WatchmanPathEvent.of(root, Kind.CREATE, ForwardRelPath.of("dir2/foo/quux/baz.txt"));

    // Invalidate caches accordingly
    FileHashCacheEvent.InvalidationStarted started = FileHashCacheEvent.invalidationStarted();
    cache.getInvalidator().onInvalidationStart(started);
    cache.getInvalidator().onFileSystemChange(event);
    cache.getInvalidator().onInvalidationFinish(FileHashCacheEvent.invalidationFinished(started));

    // The root should not be invalidated
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("")));
    assertTrue(dlist.isPresent());

    // dir2 should not be invalidated
    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir2")));
    assertTrue(dlist.isPresent());

    // dir2/foo should be invalidated
    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir2/foo")));
    assertFalse(dlist.isPresent());

    // dir2/foo/bar should not be invalidated
    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir2/foo/bar")));
    assertTrue(dlist.isPresent());
  }
}
