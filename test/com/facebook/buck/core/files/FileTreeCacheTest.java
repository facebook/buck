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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class FileTreeCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void canReadAndWrite() {
    FileTreeCache cache = FileTreeCache.of(tmp.getRoot());

    DirectoryList dlist =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(Paths.get("folder")),
            ImmutableSortedSet.of(Paths.get("symlink")));

    cache.put(
        ImmutableFileTreeKey.of(Paths.get("")),
        ImmutableFileTree.of(Paths.get(""), dlist, ImmutableMap.of()));

    Optional<FileTree> ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("")));
    assertTrue(ftree.isPresent());
    assertEquals(ftree.get().getDirectoryList(), dlist);
    assertEquals(ftree.get().getChildren(), ImmutableMap.of());

    Optional<FileTree> dlistEmpty = cache.get(ImmutableFileTreeKey.of(Paths.get("nonexisting")));
    assertFalse(dlistEmpty.isPresent());
  }

  private Object getInvalidateParameters() {
    return new Object[] {WatchmanPathEvent.Kind.CREATE, WatchmanPathEvent.Kind.DELETE};
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeThenInvalidateTreeUpNotDown(WatchmanPathEvent.Kind kind) {
    DirectoryList dlist1 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file1")),
            ImmutableSortedSet.of(Paths.get("dir1")),
            ImmutableSortedSet.of());

    DirectoryList dlist2 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file2")),
            ImmutableSortedSet.of(Paths.get("dir2")),
            ImmutableSortedSet.of());

    DirectoryList dlist3 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file3")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of());

    FileTree ftree3 = ImmutableFileTree.of(Paths.get("dir1/dir2"), dlist3, ImmutableMap.of());
    FileTree ftree2 =
        ImmutableFileTree.of(
            Paths.get("dir1"), dlist2, ImmutableMap.of(Paths.get("dir1/dir2"), ftree3));
    FileTree ftree1 =
        ImmutableFileTree.of(Paths.get(""), dlist1, ImmutableMap.of(Paths.get("dir1"), ftree2));

    FileTreeCache cache = FileTreeCache.of(tmp.getRoot());
    cache.put(ImmutableFileTreeKey.of(Paths.get("")), ftree1);
    cache.put(ImmutableFileTreeKey.of(Paths.get("dir1")), ftree2);
    cache.put(ImmutableFileTreeKey.of(Paths.get("dir1/dir2")), ftree3);

    WatchmanPathEvent event = WatchmanPathEvent.of(tmp.getRoot(), kind, Paths.get("dir1/file2"));
    cache.getInvalidator().onFileSystemChange(event);

    // all trees up should be invalidated
    Optional<FileTree> ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("dir1")));
    assertFalse(ftree.isPresent());
    ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("")));
    assertFalse(ftree.isPresent());

    // all trees down should stay
    ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("dir1/dir2")));
    assertTrue(ftree.isPresent());
  }

  @Test
  @Parameters(method = "getInvalidateParameters")
  public void whenFileListChangeAtRootThenInvalidate(WatchmanPathEvent.Kind kind) {
    FileTreeCache cache = FileTreeCache.of(tmp.getRoot());
    cache.put(
        ImmutableFileTreeKey.of(Paths.get("")),
        ImmutableFileTree.of(
            Paths.get(""),
            ImmutableDirectoryList.of(
                ImmutableSortedSet.of(Paths.get("file")),
                ImmutableSortedSet.of(),
                ImmutableSortedSet.of()),
            ImmutableMap.of()));

    WatchmanPathEvent event = WatchmanPathEvent.of(tmp.getRoot(), kind, Paths.get("file1"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<FileTree> ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("")));
    assertFalse(ftree.isPresent());
  }

  @Test
  public void whenFileListNotChangeThenNotInvalidate() {
    FileTreeCache cache = FileTreeCache.of(tmp.getRoot());
    cache.put(
        ImmutableFileTreeKey.of(Paths.get("")),
        ImmutableFileTree.of(
            Paths.get(""),
            ImmutableDirectoryList.of(
                ImmutableSortedSet.of(Paths.get("file")),
                ImmutableSortedSet.of(),
                ImmutableSortedSet.of()),
            ImmutableMap.of()));

    // should not invalidate
    WatchmanPathEvent event =
        WatchmanPathEvent.of(tmp.getRoot(), WatchmanPathEvent.Kind.MODIFY, Paths.get("file"));
    cache.getInvalidator().onFileSystemChange(event);
    Optional<FileTree> ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("")));
    assertTrue(ftree.isPresent());
  }

  @Test
  public void whenOverflowThenInvalidateAll() {
    FileTreeCache cache = FileTreeCache.of(tmp.getRoot());
    cache.put(
        ImmutableFileTreeKey.of(Paths.get("")),
        ImmutableFileTree.of(
            Paths.get(""),
            ImmutableDirectoryList.of(
                ImmutableSortedSet.of(Paths.get("file")),
                ImmutableSortedSet.of(),
                ImmutableSortedSet.of()),
            ImmutableMap.of()));

    // should not invalidate
    WatchmanOverflowEvent event = WatchmanOverflowEvent.of(tmp.getRoot(), "Test");
    cache.getInvalidator().onFileSystemChange(event);
    Optional<FileTree> ftree = cache.get(ImmutableFileTreeKey.of(Paths.get("")));
    assertFalse(ftree.isPresent());
  }
}
