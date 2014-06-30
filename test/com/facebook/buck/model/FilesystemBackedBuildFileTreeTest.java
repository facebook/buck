/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

public class FilesystemBackedBuildFileTreeTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test @Ignore("Remove when test passes on OS X (the case preserving file system hurts us)")
  public void testCanConstructBuildFileTreeFromFilesystemOnOsX() throws IOException {
    File tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    File command = new File(tempDir, "src/com/facebook/buck/command");
    assertTrue(command.mkdirs());
    File notbuck = new File(tempDir, "src/com/facebook/buck/notbuck");
    assertTrue(notbuck.mkdirs());

    // Although these next two lines create a file and a directory, the OS X filesystem is often
    // case insensitive. As we run File.listFiles only the directory entry is returned. Thanks OS X.
    Files.touch(new File(tempDir, "src/com/facebook/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/command/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/notbuck/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem);
    Iterable<Path> allChildren =
        buildFiles.getChildPaths(BuildTarget.builder("src", "com/facebook").build());
    assertEquals(ImmutableSet.of(Paths.get("buck")),
        ImmutableSet.copyOf(allChildren));

    Iterable<Path> subChildren = buildFiles.getChildPaths(
        BuildTarget.builder("//src", "/com/facebook/buck").build());
    assertEquals(ImmutableSet.of(Paths.get("command"), Paths.get("notbuck")),
        ImmutableSet.copyOf(subChildren));
  }

  @Test
  public void testCanConstructBuildFileTreeFromFilesystem() throws IOException {
    File tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    File command = new File(tempDir, "src/com/example/build/command");
    assertTrue(command.mkdirs());
    File notbuck = new File(tempDir, "src/com/example/build/notbuck");
    assertTrue(notbuck.mkdirs());
    assertTrue((new File(tempDir, "src/com/example/some/directory")).mkdirs());

    Files.touch(new File(tempDir, "src/com/example/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/command/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/notbuck/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/some/directory/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem);
    Collection<Path> allChildren = buildFiles.getChildPaths(
        BuildTargetFactory.newInstance("//src/com/example:example"));
    assertEquals(ImmutableSet.of(Paths.get("build"), Paths.get("some/directory")),
        ImmutableSet.copyOf(allChildren));

    Iterable<Path> subChildren = buildFiles.getChildPaths(
        BuildTargetFactory.newInstance("//src/com/example/build:build"));
    assertEquals(ImmutableSet.of(Paths.get("command"), Paths.get("notbuck")),
        ImmutableSet.copyOf(subChildren));

    assertEquals(Paths.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(Paths.get("src/com/example/foo")));
    assertEquals(Paths.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(Paths.get("src/com/example/some/bar")));
    assertEquals(Paths.get("src/com/example/some/directory"),
        buildFiles.getBasePathOfAncestorTarget(Paths.get("src/com/example/some/directory/baz")));
  }

  @Test
  public void respectsIgnorePaths() throws IOException {
    File tempDir = tmp.getRoot();
    File fooBuck = new File(tempDir, "foo/BUCK");
    File fooBarBuck = new File(tempDir, "foo/bar/BUCK");
    File fooBazBuck = new File(tempDir, "foo/baz/BUCK");
    Files.createParentDirs(fooBarBuck);
    Files.createParentDirs(fooBazBuck);
    Files.touch(fooBuck);
    Files.touch(fooBarBuck);
    Files.touch(fooBazBuck);

    ImmutableSet<Path> ignoredPaths = ImmutableSet.of(Paths.get("foo/bar"));
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir.toPath(), ignoredPaths);
    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem);

    Collection<Path> children =
        buildFiles.getChildPaths(BuildTarget.builder("//foo", "foo").build());
    assertEquals(ImmutableSet.of(Paths.get("baz")), children);

    Path ancestor = buildFiles.getBasePathOfAncestorTarget(Paths.get("foo/bar/xyzzy"));
    assertEquals(Paths.get("foo"), ancestor);
  }
}
