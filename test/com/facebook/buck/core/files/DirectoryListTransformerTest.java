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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.graph.transformation.FakeComputationEnvironment;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DirectoryListTransformerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void canListDirectory() throws Exception {
    Path dir = tmp.getRoot().resolve("dir");
    Path dir1 = dir.resolve("dir1");
    Path file1 = dir.resolve("file1");
    Path file2 = dir.resolve("file2");
    Path link1 = dir.resolve("link1");
    Files.createDirectory(dir);
    Files.createDirectory(dir1);
    Files.createFile(file1);
    Files.createFile(file2);
    Files.createSymbolicLink(link1, file1);

    DirectoryListComputation transformer = DirectoryListComputation.of(filesystem.asView());
    DirectoryList dirList =
        transformer.transform(
            ImmutableDirectoryListKey.of(dir), new FakeComputationEnvironment(ImmutableMap.of()));
    ImmutableSortedSet<Path> files = dirList.getFiles();
    assertEquals(2, files.size());
    assertTrue(files.contains(Paths.get("dir/file1")));
    assertTrue(files.contains(Paths.get("dir/file2")));

    ImmutableSortedSet<Path> dirs = dirList.getDirectories();
    assertEquals(1, dirs.size());
    assertTrue(dirs.contains(Paths.get("dir/dir1")));

    ImmutableSortedSet<Path> links = dirList.getSymlinks();
    assertEquals(1, links.size());
    assertTrue(links.contains(Paths.get("dir/link1")));
  }
}
