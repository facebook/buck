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

package com.facebook.buck.io.filesystem.impl;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ExactPathMatcher;
import com.facebook.buck.io.filesystem.RecursiveFileMatcher;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultProjectFilesystemViewTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expected = ExpectedException.none();

  private DefaultProjectFilesystem filesystem;
  private DefaultProjectFilesystemView filesystemView;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    filesystemView =
        new DefaultProjectFilesystemView(
            filesystem, Paths.get(""), filesystem.getRootPath(), ImmutableMap.of());
  }

  @Test
  public void resolvedPathsAreResolvedRelativeToViewRoot() {
    assertEquals(tmp.getRoot().resolve(Paths.get("foo")), filesystemView.resolve(Paths.get("foo")));
    assertEquals(tmp.getRoot().resolve(Paths.get("foo")), filesystemView.resolve("foo"));

    filesystemView = filesystemView.withView(Paths.get("foo"), ImmutableSet.of());

    assertEquals(
        tmp.getRoot().resolve(Paths.get("foo", "bar")), filesystemView.resolve(Paths.get("bar")));
    assertEquals(tmp.getRoot().resolve(Paths.get("foo", "bar")), filesystemView.resolve("bar"));
  }

  @Test
  public void isSubdirOfShouldObeyViewRoot() {
    assertTrue(filesystemView.isSubdirOf(tmp.getRoot().resolve("foo")));
    assertFalse(filesystemView.isSubdirOf(tmp.getRoot().resolve("..")));

    filesystemView = filesystemView.withView(Paths.get("foo"), ImmutableSet.of());
    assertTrue(filesystemView.isSubdirOf(tmp.getRoot().resolve("foo").resolve("bar")));
    assertTrue(filesystemView.isSubdirOf(tmp.getRoot().resolve("foo")));
    assertFalse(filesystemView.isSubdirOf(tmp.getRoot()));
  }

  @Test
  public void isDirectoryDelegatesToFilesystem() throws IOException {
    filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo"));
    filesystemView =
        new DefaultProjectFilesystemView(
            filesystem, Paths.get(""), filesystem.getRootPath(), ImmutableMap.of());

    assertTrue(filesystemView.isDirectory(Paths.get("foo")));
    assertFalse(filesystemView.isDirectory(Paths.get("bar")));
  }

  @Test
  public void relativizeReturnsPathsRelativeToViewRoot() {
    assertEquals(filesystem.relativize(tmp.getRoot()), filesystemView.relativize(tmp.getRoot()));
    filesystemView = filesystemView.withView(Paths.get("foo"), ImmutableSet.of());
    assertEquals(
        Paths.get("bar"),
        filesystemView.relativize(tmp.getRoot().resolve(Paths.get("foo", "bar"))));
    filesystemView = filesystemView.withView(Paths.get("bar"), ImmutableSet.of());
    assertEquals(
        Paths.get(""), filesystemView.relativize(tmp.getRoot().resolve(Paths.get("foo", "bar"))));
  }

  @Test
  public void fileSystemViewHandlesIgnoresProperlyWithDifferentRoots() {
    assertFalse(filesystemView.isIgnored(Paths.get("foo")));
    filesystemView =
        filesystemView.withView(
            Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(Paths.get("foo"))));

    // matcher was declared in view relative to ".", so should only match "foo", but not any
    // "bar/foo" etc
    assertTrue(filesystemView.isIgnored(Paths.get("foo", "bar")));
    assertFalse(filesystemView.isIgnored(Paths.get("bar", "foo")));

    filesystemView = filesystemView.withView(Paths.get("bar"), ImmutableSet.of());
    // we are now under "./bar", which is not the original ignored paths of "./foo"
    assertFalse(filesystemView.isIgnored(Paths.get("foo")));
    assertTrue(filesystemView.isIgnored(Paths.get("..", "foo")));

    filesystemView =
        filesystemView.withView(
            Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(Paths.get("a", "path"))));
    filesystemView = filesystemView.withView(Paths.get("a"), ImmutableSet.of());
    assertTrue(filesystemView.isIgnored(Paths.get("path")));
    assertFalse(filesystemView.isIgnored(Paths.get("a", "path")));
  }

  @Test
  public void walkRelativeFileTreeOnlyReturnsPathsWithinViewRoot() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");

    ImmutableList.Builder<Path> fileNames = ImmutableList.builder();

    filesystemView.walkRelativeFileTree(
        Paths.get("dir"),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file);
            return FileVisitResult.CONTINUE;
          }
        });
    assertThat(
        fileNames.build(),
        containsInAnyOrder(Paths.get("dir/file.txt"), Paths.get("dir/dir2/file2.txt")));

    ImmutableList.Builder<Path> fileNames2 = ImmutableList.builder();

    filesystemView = filesystemView.withView(Paths.get("dir"), ImmutableSet.of());
    filesystemView.walkRelativeFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames2.add(file);
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(
        fileNames2.build(), containsInAnyOrder(Paths.get("file.txt"), Paths.get("dir2/file2.txt")));
  }

  @Test
  public void walkRelativeWalkTreeIgnoresIgnoredFilesInView() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");

    ImmutableList.Builder<Path> fileNames = ImmutableList.builder();

    filesystemView =
        filesystemView.withView(
            Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(Paths.get("dir", "dir2"))));
    filesystemView.walkRelativeFileTree(
        Paths.get("dir"),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file);
            return FileVisitResult.CONTINUE;
          }
        });
    assertThat(fileNames.build(), containsInAnyOrder(Paths.get("dir/file.txt")));

    ImmutableList.Builder<Path> fileNames2 = ImmutableList.builder();
    filesystemView = filesystemView.withView(Paths.get("dir"), ImmutableSet.of());
    filesystemView.walkRelativeFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames2.add(file);
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames2.build(), containsInAnyOrder(Paths.get("file.txt")));

    ImmutableList.Builder<Path> fileNames3 = ImmutableList.builder();
    filesystemView =
        filesystemView.withView(
            Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(Paths.get("dir"))));
    filesystemView.walkRelativeFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames3.add(file);
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames3.build(), containsInAnyOrder(Paths.get("file.txt")));

    ImmutableList.Builder<Path> fileNames4 = ImmutableList.builder();
    filesystemView =
        filesystemView.withView(
            Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(Paths.get("file.txt"))));
    filesystemView.walkRelativeFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames4.add(file);
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames4.build(), containsInAnyOrder());
  }

  @Test
  public void walkFileTreeReturnsAbsolutePaths() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");
    ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    filesystemView.walkFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            assertTrue(file.isAbsolute());
            fileNames.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames.build(), containsInAnyOrder("file.txt", "file2.txt"));

    filesystemView = filesystemView.withView(Paths.get("dir"), ImmutableSet.of());
    ImmutableList.Builder<String> fileNames2 = ImmutableList.builder();
    filesystemView.walkFileTree(
        Paths.get(""),
        EnumSet.noneOf(FileVisitOption.class),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            assertTrue(file.isAbsolute());
            fileNames2.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames2.build(), containsInAnyOrder("file.txt", "file2.txt"));
  }

  @Test
  public void getFilesUnderPathIgnoresFilesOutsideViewRoot() throws IOException {
    tmp.newFile("file1");
    tmp.newFolder("dir1");
    tmp.newFile("dir1/file2");
    tmp.newFolder("dir1/dir2");
    tmp.newFile("dir1/dir2/file3");

    assertThat(
        filesystemView.getFilesUnderPath(Paths.get("dir1"), EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystemView.getFilesUnderPath(Paths.get("dir1"), EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    filesystemView =
        filesystemView.withView(Paths.get("dir1"), ImmutableSet.of(ExactPathMatcher.of("file2")));
    assertThat(
        filesystemView.getFilesUnderPath(Paths.get(""), EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir2/file3")));

    assertThat(
        filesystemView.getFilesUnderPath(
            Paths.get(""),
            Paths.get("dir1/file2")::equals,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS)),
        containsInAnyOrder());
  }

  @Test
  public void getDirectoryContentsWithEmptyRoot() throws IOException {
    tmp.newFolder("dir1");
    tmp.newFile("dir1/file1");
    tmp.newFile("dir1/file2");

    assertThat(
        filesystemView.getDirectoryContents(Paths.get("dir1")),
        containsInAnyOrder(Paths.get("dir1/file1"), Paths.get("dir1/file2")));
  }
}
