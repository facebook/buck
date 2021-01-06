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

package com.facebook.buck.core.model.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class FilesystemBackedBuildFileTreeTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testCanConstructBuildFileTreeFromFilesystem() throws IOException {
    Path tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir);

    Path command = tempDir.resolve("src/com/example/build/command");
    Files.createDirectories(command);
    Path notbuck = tempDir.resolve("src/com/example/build/notbuck");
    Files.createDirectories(notbuck);
    Files.createDirectories(tempDir.resolve("src/com/example/some/directory"));

    touch(tempDir.resolve("src/com/example/BUCK"));
    touch(tempDir.resolve("src/com/example/build/BUCK"));
    touch(tempDir.resolve("src/com/example/build/command/BUCK"));
    touch(tempDir.resolve("src/com/example/build/notbuck/BUCK"));
    touch(tempDir.resolve("src/com/example/some/directory/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    assertEquals(
        RelPath.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(RelPath.get("src/com/example/foo")).get());
    assertEquals(
        RelPath.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(RelPath.get("src/com/example/some/bar")).get());
    assertEquals(
        RelPath.get("src/com/example/some/directory"),
        buildFiles
            .getBasePathOfAncestorTarget(RelPath.get("src/com/example/some/directory/baz"))
            .get());
  }

  @Test
  public void respectsIgnorePaths() throws IOException {
    Path tempDir = tmp.getRoot();
    Path fooBuck = tempDir.resolve("foo/BUCK");
    Path fooBarBuck = tempDir.resolve("foo/bar/BUCK");
    Path fooBazBuck = tempDir.resolve("foo/baz/BUCK");
    Files.createDirectories(fooBarBuck.getParent());
    Files.createDirectories(fooBazBuck.getParent());
    touch(fooBuck);
    touch(fooBarBuck);
    touch(fooBazBuck);

    Config config = ConfigBuilder.createFromText("[project]", "ignore = foo/bar");
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir, config);
    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    RelPath ancestor = buildFiles.getBasePathOfAncestorTarget(RelPath.get("foo/bar/xyzzy")).get();
    assertEquals(RelPath.get("foo"), ancestor);
  }

  @Test
  public void rootBasePath() throws IOException {
    Path root = tmp.getRoot();
    Files.createFile(root.resolve("BUCK"));
    Files.createDirectory(root.resolve("foo"));
    Files.createFile(root.resolve("foo/BUCK"));

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<RelPath> ancestor = buildFileTree.getBasePathOfAncestorTarget(RelPath.get("bar/baz"));
    assertEquals(Optional.of(RelPath.get("")), ancestor);
  }

  @Test
  public void missingBasePath() throws IOException {
    Path root = tmp.getRoot();
    Files.createDirectory(root.resolve("foo"));
    Files.createFile(root.resolve("foo/BUCK"));

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<RelPath> ancestor = buildFileTree.getBasePathOfAncestorTarget(RelPath.get("bar/baz"));
    assertEquals(Optional.empty(), ancestor);
  }

  @Test
  public void shouldIgnoreBuckOutputDirectoriesByDefault() throws IOException {
    Path root = tmp.getRoot();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(root, new Config());

    Path buckOut = root.resolve(filesystem.getBuckPaths().getBuckOut());
    Files.createDirectories(buckOut);
    touch(buckOut.resolve("BUCK"));
    Path sibling = buckOut.resolve("someFile");
    touch(sibling);

    // Config doesn't set any "ignore" entries.
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<RelPath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(
            RelPath.of(filesystem.getBuckPaths().getBuckOut().resolve("someFile")));
    assertFalse(ancestor.isPresent());
  }

  @Test
  public void shouldIgnoreBuckCacheDirectoriesByDefault() throws IOException {
    Path root = tmp.getRoot();

    RelPath cacheDir = RelPath.get("buck-out/cache");
    Files.createDirectories(tmp.getRoot().resolve(cacheDir.getPath()));
    touch(tmp.getRoot().resolve(cacheDir.resolve("BUCK")));
    Path sibling = cacheDir.resolve("someFile");
    touch(tmp.getRoot().resolve(sibling));

    // Config doesn't set any "ignore" entries.
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(root, new Config());
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<RelPath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(cacheDir.resolveRel("someFile"));
    assertFalse(ancestor.isPresent());
  }

  private void touch(Path path) throws IOException {
    Files.write(path, "".getBytes(UTF_8));
  }
}
