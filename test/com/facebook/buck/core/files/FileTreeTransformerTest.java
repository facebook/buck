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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.graph.transformation.FakeComputationEnvironment;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class FileTreeTransformerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void canBuildTree() throws Exception {
    Path dir = tmp.getRoot().resolve("dir");
    Path file = dir.resolve("file");
    Path dir1 = dir.resolve("dir1");
    Path file1 = dir1.resolve("file1");
    Files.createDirectory(dir);
    Files.createDirectory(dir1);
    Files.createFile(file);
    Files.createFile(file1);

    FileTreeComputation transformer = FileTreeComputation.of();

    DirectoryList dlist =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir/file")),
            ImmutableSortedSet.of(Paths.get("dir/dir1")),
            ImmutableSortedSet.of());
    DirectoryListKey dkey = ImmutableDirectoryListKey.of(Paths.get("dir"));
    DirectoryList dlist1 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir/dir1/file1")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of());
    DirectoryListKey dkey1 = ImmutableDirectoryListKey.of(Paths.get("dir/dir1"));

    FileTreeKey fkey = ImmutableFileTreeKey.of(Paths.get("dir/dir1"));
    FileTree ftree = ImmutableFileTree.of(fkey.getPath(), dlist1, ImmutableMap.of());

    FakeComputationEnvironment env =
        new FakeComputationEnvironment(ImmutableMap.of(dkey, dlist, dkey1, dlist1, fkey, ftree));
    FileTree fileTree = transformer.transform(ImmutableFileTreeKey.of(Paths.get("dir")), env);

    ImmutableSortedSet<Path> dirs = fileTree.getDirectoryList().getDirectories();
    assertEquals(1, dirs.size());
    assertTrue(dirs.contains(Paths.get("dir/dir1")));

    ImmutableSortedSet<Path> files = fileTree.getDirectoryList().getFiles();
    assertEquals(1, files.size());
    assertTrue(files.contains(Paths.get("dir/file")));

    ImmutableMap<Path, FileTree> children = fileTree.getChildren();
    assertEquals(1, children.size());

    FileTree child = children.get(Paths.get("dir/dir1"));
    assertNotNull(child);

    dirs = child.getDirectoryList().getDirectories();
    assertEquals(0, dirs.size());

    files = child.getDirectoryList().getFiles();
    assertEquals(1, files.size());
    assertTrue(files.contains(Paths.get("dir/dir1/file1")));

    children = child.getChildren();
    assertEquals(0, children.size());
  }
}
