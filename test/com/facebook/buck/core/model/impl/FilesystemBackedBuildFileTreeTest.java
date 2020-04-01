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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.path.ForwardRelativePath;
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
    AbsPath tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir);

    AbsPath command = tempDir.resolve("src/com/example/build/command");
    Files.createDirectories(command.getPath());
    AbsPath notbuck = tempDir.resolve("src/com/example/build/notbuck");
    Files.createDirectories(notbuck.getPath());
    Files.createDirectories(tempDir.resolve("src/com/example/some/directory").getPath());

    touch(tempDir.resolve("src/com/example/BUCK"));
    touch(tempDir.resolve("src/com/example/build/BUCK"));
    touch(tempDir.resolve("src/com/example/build/command/BUCK"));
    touch(tempDir.resolve("src/com/example/build/notbuck/BUCK"));
    touch(tempDir.resolve("src/com/example/some/directory/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    assertEquals(
        ForwardRelativePath.of("src/com/example"),
        buildFiles
            .getBasePathOfAncestorTarget(ForwardRelativePath.of("src/com/example/foo"))
            .get());
    assertEquals(
        ForwardRelativePath.of("src/com/example"),
        buildFiles
            .getBasePathOfAncestorTarget(ForwardRelativePath.of("src/com/example/some/bar"))
            .get());
    assertEquals(
        ForwardRelativePath.of("src/com/example/some/directory"),
        buildFiles
            .getBasePathOfAncestorTarget(
                ForwardRelativePath.of("src/com/example/some/directory/baz"))
            .get());
  }

  @Test
  public void respectsIgnorePaths() throws IOException {
    AbsPath tempDir = tmp.getRoot();
    AbsPath fooBuck = tempDir.resolve("foo/BUCK");
    AbsPath fooBarBuck = tempDir.resolve("foo/bar/BUCK");
    AbsPath fooBazBuck = tempDir.resolve("foo/baz/BUCK");
    Files.createDirectories(fooBarBuck.getParent().getPath());
    Files.createDirectories(fooBazBuck.getParent().getPath());
    touch(fooBuck);
    touch(fooBarBuck);
    touch(fooBazBuck);

    Config config = ConfigBuilder.createFromText("[project]", "ignore = foo/bar");
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir, config);
    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    ForwardRelativePath ancestor =
        buildFiles.getBasePathOfAncestorTarget(ForwardRelativePath.of("foo/bar/xyzzy")).get();
    assertEquals(ForwardRelativePath.of("foo"), ancestor);
  }

  @Test
  public void rootBasePath() throws IOException {
    AbsPath root = tmp.getRoot();
    Files.createFile(root.resolve("BUCK").getPath());
    Files.createDirectory(root.resolve("foo").getPath());
    Files.createFile(root.resolve("foo/BUCK").getPath());

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<ForwardRelativePath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(ForwardRelativePath.of("bar/baz"));
    assertEquals(Optional.of(ForwardRelativePath.of("")), ancestor);
  }

  @Test
  public void missingBasePath() throws IOException {
    AbsPath root = tmp.getRoot();
    Files.createDirectory(root.resolve("foo").getPath());
    Files.createFile(root.resolve("foo/BUCK").getPath());

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<ForwardRelativePath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(ForwardRelativePath.of("bar/baz"));
    assertEquals(Optional.empty(), ancestor);
  }

  @Test
  public void shouldIgnoreBuckOutputDirectoriesByDefault() throws IOException {
    AbsPath root = tmp.getRoot();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(root, new Config());

    AbsPath buckOut = root.resolve(filesystem.getBuckPaths().getBuckOut());
    Files.createDirectories(buckOut.getPath());
    touch(buckOut.resolve("BUCK"));
    AbsPath sibling = buckOut.resolve("someFile");
    touch(sibling);

    // Config doesn't set any "ignore" entries.
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<ForwardRelativePath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(
            ForwardRelativePath.ofPath(filesystem.getBuckPaths().getBuckOut().resolve("someFile")));
    assertFalse(ancestor.isPresent());
  }

  @Test
  public void shouldIgnoreBuckCacheDirectoriesByDefault() throws IOException {
    AbsPath root = tmp.getRoot();

    RelPath cacheDir = RelPath.get("buck-out/cache");
    Files.createDirectories(tmp.getRoot().resolve(cacheDir.getPath()).getPath());
    touch(tmp.getRoot().resolve(cacheDir.resolve("BUCK")));
    Path sibling = cacheDir.resolve("someFile");
    touch(tmp.getRoot().resolve(sibling));

    // Config doesn't set any "ignore" entries.
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(root, new Config());
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<ForwardRelativePath> ancestor =
        buildFileTree.getBasePathOfAncestorTarget(
            ForwardRelativePath.ofRelPath(cacheDir.resolveRel("someFile")));
    assertFalse(ancestor.isPresent());
  }

  private void touch(AbsPath path) throws IOException {
    Files.write(path.getPath(), "".getBytes(UTF_8));
  }
}
