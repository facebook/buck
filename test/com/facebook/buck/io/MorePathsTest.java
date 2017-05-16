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

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class MorePathsTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testGetRelativePath() {
    // Path on base directory.
    assertEquals(Paths.get("file"), MorePaths.getRelativePath(Paths.get("file"), Paths.get("")));

    // Path on base directory (using null).
    assertEquals(Paths.get("file"), MorePaths.getRelativePath(Paths.get("file"), null));

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
    Path root = tmp.getRoot();
    ImmutableSet<Path> paths =
        RichStream.of(
                ".buckd",
                "foo/bar",
                root.toAbsolutePath() + "/buck-cache",
                Paths.get("/root/not/in/test").toAbsolutePath().toString())
            .map(Paths::get)
            .toImmutableSet();

    assertEquals(
        "Set should have been filtered down to paths contained under root.",
        ImmutableSet.of(Paths.get(".buckd"), Paths.get("foo/bar"), Paths.get("buck-cache")),
        MorePaths.filterForSubpaths(paths, root));
  }

  @Test
  public void testCreateRelativeSymlinkToFilesInRoot() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());
    tmp.newFile("biz.txt");

    Path pathToDesiredLinkUnderProjectRoot = Paths.get("gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("biz.txt");
    Path relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);
    assertEquals("biz.txt", relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFileInRoot() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());
    tmp.newFile("biz.txt");

    tmp.newFolder("alpha", "beta");
    Path pathToDesiredLinkUnderProjectRoot = Paths.get("alpha/beta/gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("biz.txt");
    Path relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);
    assertEquals(Paths.get("../../biz.txt").toString(), relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFilesOfVaryingDepth()
      throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());
    tmp.newFolder("foo", "bar", "baz");
    tmp.newFile("foo/bar/baz/biz.txt");

    tmp.newFolder("alpha", "beta");
    Path pathToDesiredLinkUnderProjectRoot = Paths.get("alpha/beta/gamma.txt");
    Path pathToExistingFileUnderProjectRoot = Paths.get("foo/bar/baz/biz.txt");
    Path relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);
    assertEquals(Paths.get("../../foo/bar/baz/biz.txt").toString(), relativePath.toString());

    Path absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot));
    Path targetOfSymbolicLink = Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot);
    assertEquals(relativePath, targetOfSymbolicLink);

    Path absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot, "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot));
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
        "Must expand home dir by itself too.", homeDir, MorePaths.expandHomeDir(Paths.get("~")));

    Path relativePath = Paths.get("foo/bar");
    assertEquals(
        "Must not expand relative paths.", relativePath, MorePaths.expandHomeDir(relativePath));

    Path absolutePath = Paths.get("/foo/bar");
    assertEquals(
        "Must not expand absolute paths.", absolutePath, MorePaths.expandHomeDir(absolutePath));

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

  @Test
  public void relativeWithEmptyPath() {
    // Ensure workaround for Windows Path relativize bug
    Path p1 = Paths.get("");
    Path p2 = Paths.get("foo");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("foo")));
  }

  @Test
  public void normalizeWithEmptyPath() {
    // Ensure workaround for Java Path normalize bug
    Path emptyPath = Paths.get("");
    assertThat(MorePaths.normalize(emptyPath), equalTo(emptyPath));
  }

  @Test
  public void fixPathCommonTest() {
    final Path inputPath = Paths.get("subdir/subdir2/foo/bar/x.file");
    final Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathAbsoluteTest() {
    final Path inputPath = Paths.get("/subdir/subdir2/foo/bar/x.file");
    final Path expecting = Paths.get("/subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathEmptyPartTest() {
    final Path inputPath = Paths.get("subdir/subdir2//foo///bar/////x.file");
    final Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathDotPartTest() {
    final Path inputPath = Paths.get("subdir/subdir2/./foo/././bar/./x.file");
    final Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathDotDotPartTest() {
    final Path inputPath = Paths.get("subdir/subdir2/foo/../bar/x.file");
    final Path expecting = Paths.get("subdir/subdir2/foo/../bar/x.file");
    // should be the same!  Does not fully "normalize" path.
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void getNameWithoutExtension() {
    Path inputPath = Paths.get("subdir/subdir2/bar/x.file");
    assertEquals("x", MorePaths.getNameWithoutExtension(inputPath));
  }
}
