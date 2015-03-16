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
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

  private Path createExecutable(String executablePath) throws IOException {
    File file = tmp.newFile(executablePath);
    file.setExecutable(true);
    return file.toPath();
  }

  @Test
  public void testSearchPathsFileFoundReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsNonExecutableFileIsIgnored() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    // Note this is not executable.
    tmp.newFile("foo/blech");
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsDirAndFileFoundReturnsFileNotDir() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    // We don't want to find this folder.
    tmp.newFolder("foo/foo");
    Path dir2 = tmp.newFolder("bar").toPath();
    Path file = createExecutable("bar/foo");

    assertEquals(
        Optional.of(file),
        MorePaths.searchPathsForExecutable(
            Paths.get("foo"),
            ImmutableList.of(dir1, dir2),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsMultipleFileFoundReturnsFirstPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file1 = createExecutable("bar/blech");
    createExecutable("baz/blech");

    assertEquals(
        Optional.of(file1),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsSymlinkToExecutableOutsideSearchPathReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    tmp.newFolder("unsearched");
    Path binary = createExecutable("unsearched/binary");
    Path file1 = dir2.resolve("blech");
    Files.createSymbolicLink(file1, binary);

    assertEquals(
        Optional.of(file1),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsFileNotFoundReturnsAbsent() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();

    assertEquals(
        Optional.<Path>absent(),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsEmptyReturnsAbsent() throws IOException {
    assertEquals(
        Optional.<Path>absent(),
        MorePaths.searchPathsForExecutable(
            Paths.get("blech"),
            ImmutableList.<Path>of(),
            ImmutableList.<String>of()));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void testSearchPathsWithIsExecutableFunctionSuccess() {
    Path path1 = Paths.get("foo/bar");
    assertEquals(
        Optional.of(path1),
        MorePaths.searchPathsForExecutable(
            Paths.get("bar"),
            ImmutableList.of(Paths.get("baz"), Paths.get("foo")),
            ImmutableList.<String>of(),
            Functions.forMap(
                ImmutableMap.of(
                    path1,
                    true),
                false)));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void testSearchPathsWithIsExecutableFunctionFailure() {
    assertEquals(
        Optional.<Path>absent(),
        MorePaths.searchPathsForExecutable(
            Paths.get("bar"),
            ImmutableList.of(Paths.get("baz", "foo")),
            ImmutableList.<String>of(),
            Functions.forPredicate(Predicates.<Path>alwaysFalse())));
  }

  @Test
  public void testSearchPathsWithExtensions() throws IOException {
    Path dir = tmp.newFolder("foo").toPath();
    Path file = createExecutable("foo/bar.EXE");

    assertEquals(
        Optional.of(file),
        MorePaths.searchPathsForExecutable(
            Paths.get("bar"),
            ImmutableList.of(dir),
            ImmutableList.of(".BAT", ".EXE")));
  }

  @Test
  public void testSearchPathsWithExtensionsNoMatch() throws IOException {
    Path dir = tmp.newFolder("foo").toPath();
    createExecutable("foo/bar.COM");

    assertEquals(
        Optional.absent(),
        MorePaths.searchPathsForExecutable(
            Paths.get("bar"),
            ImmutableList.of(dir),
            ImmutableList.of(".BAT", ".EXE")));
  }
}
