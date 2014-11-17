/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.io;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MorePathsTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testGetRelativePath() {
    // Path on base directory.
    assertEquals(
        Paths.get("file"),
        MorePaths.getRelativePath(Paths.get("file"), Paths.get("")));

    // Path on base directory (using null).
    assertEquals(
        Paths.get("file"),
        MorePaths.getRelativePath(Paths.get("file"), null));

    // Path internal to base directory.
    assertEquals(
        Paths.get("dir/file"),
        MorePaths.getRelativePath(Paths.get("base/dir/file"), Paths.get("base")));

    // Path external to base directory.
    assertEquals(
        Paths.get("../dir1/file"),
        MorePaths.getRelativePath(Paths.get("dir1/file"), Paths.get("base")));
  }

  @Test
  public void testFilterForSubpaths() {
    File root = tmp.getRoot();
    ImmutableSortedSet<Path> paths = MorePaths.asPaths(ImmutableSet.of(
      ".buckd",
      "foo/bar",
      root.getAbsolutePath() + "/buck-cache",
      "/root/not/in/test"));

    assertEquals(
        "Set should have been filtered down to paths contained under root.",
        ImmutableSet.of(
            Paths.get(".buckd"),
            Paths.get("foo/bar"),
            Paths.get("buck-cache")),
        MorePaths.filterForSubpaths(paths, root.toPath()));
  }

  @Test
  public void testCreateRelativeSymlinkToFilesInRoot() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRootPath());
    tmp.newFile("biz.txt");

    Path pathToDesiredLinkUnderProjectRoot = Paths.get("gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("biz.txt");
    Path relativePath = MorePaths.createRelativeSymlink(
        pathToDesiredLinkUnderProjectRoot,
        pathToExistingFileUnderProjectRoot,
        projectFilesystem);
    assertEquals("biz.txt", relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot = projectFilesystem.resolve(
        pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot = projectFilesystem.resolve(
        pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink = new String(Files.readAllBytes(
        absolutePathToDesiredLinkUnderProjectRoot));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFileInRoot() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRootPath());
    tmp.newFile("biz.txt");

    tmp.newFolder("alpha", "beta");
    Path pathToDesiredLinkUnderProjectRoot = Paths.get("alpha/beta/gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("biz.txt");
    Path relativePath = MorePaths.createRelativeSymlink(
        pathToDesiredLinkUnderProjectRoot,
        pathToExistingFileUnderProjectRoot,
        projectFilesystem);
    assertEquals("../../biz.txt", relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot = projectFilesystem.resolve(
        pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot = projectFilesystem.resolve(
        pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink = new String(Files.readAllBytes(
        absolutePathToDesiredLinkUnderProjectRoot));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFilesOfVaryingDepth() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRootPath());
    tmp.newFolder("foo", "bar", "baz");
    tmp.newFile("foo/bar/baz/biz.txt");

    tmp.newFolder("alpha", "beta");
    Path pathToDesiredLinkUnderProjectRoot = Paths.get("alpha/beta/gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("foo/bar/baz/biz.txt");
    Path relativePath = MorePaths.createRelativeSymlink(
        pathToDesiredLinkUnderProjectRoot,
        pathToExistingFileUnderProjectRoot,
        projectFilesystem);
    assertEquals("../../foo/bar/baz/biz.txt", relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot = projectFilesystem.resolve(
        pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot = projectFilesystem.resolve(
        pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink = new String(Files.readAllBytes(
        absolutePathToDesiredLinkUnderProjectRoot));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testExpandHomeDir() {
    Path homeDir = Paths.get(System.getProperty("user.home"));
    assertEquals(
        "Must expand home dir.",
        homeDir.resolve("foo"),
        MorePaths.expandHomeDir(Paths.get("~/foo")));

    assertEquals(
        "Must expand home dir by itself too.",
        homeDir,
        MorePaths.expandHomeDir(Paths.get("~")));

    Path relativePath = Paths.get("foo/bar");
    assertEquals(
        "Must not expand relative paths.",
        relativePath,
        MorePaths.expandHomeDir(relativePath));

    Path absolutePath = Paths.get("/foo/bar");
    assertEquals(
        "Must not expand absolute paths.",
        absolutePath,
        MorePaths.expandHomeDir(absolutePath));

    Path funnyHomePath = Paths.get("~jacko/foo");
    assertEquals(
        "Must only expand home paths starting with ~/",
        funnyHomePath,
        MorePaths.expandHomeDir(funnyHomePath));
  }

  @Test
  public void relativizeWithDotPathDoesNotAddExtraDotDotPath() {
    // Ensure workaround for bug JDK-6925169 for "." case.
    Path p1 = Paths.get("./a/b");
    Path p2 = Paths.get("c/d/e");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("../../c/d/e")));
  }

  @Test
  public void relativizeWithDotDotPathDoesNotAddExtraDotDotPath() {
    // Ensure workaround for bug JDK-6925169 for ".." case.
    Path p1 = Paths.get("a/../b");
    Path p2 = Paths.get("c/d/e");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("../c/d/e")));
  }
}
