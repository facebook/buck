/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedSet;
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
        ImmutableDirectoryList.of(
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
    return new Object[] {WatchmanPathEvent.Kind.CREATE, WatchmanPathEvent.Kind.DELETE};
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeThenInvalidate(WatchmanPathEvent.Kind kind) {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    // should not invalidate
    WatchmanPathEvent event = WatchmanPathEvent.of(tmp.getRoot(), kind, Paths.get("dir1/file"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertTrue(dlist.isPresent());

    // should invalidate
    event = WatchmanPathEvent.of(tmp.getRoot(), kind, Paths.get("dir/file1"));
    cache.getInvalidator().onFileSystemChange(event);

    dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertFalse(dlist.isPresent());
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeAtRootThenInvalidate(WatchmanPathEvent.Kind kind) {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("")),
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    WatchmanPathEvent event = WatchmanPathEvent.of(tmp.getRoot(), kind, Paths.get("file1"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("")));
    assertFalse(dlist.isPresent());
  }

  @Test
  public void whenFileListNotChangeThenNotInvalidate() {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    // should not invalidate
    WatchmanPathEvent event =
        WatchmanPathEvent.of(tmp.getRoot(), WatchmanPathEvent.Kind.MODIFY, Paths.get("dir/file"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<DirectoryList> dlist = cache.get(ImmutableDirectoryListKey.of(Paths.get("dir")));
    assertTrue(dlist.isPresent());
  }

  @Test
  public void whenOverflowThenInvalidateAll() {
    DirectoryListCache cache = DirectoryListCache.of(tmp.getRoot());
    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir")),
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of()));

    cache.put(
        ImmutableDirectoryListKey.of(Paths.get("dir1")),
        ImmutableDirectoryList.of(
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
}
