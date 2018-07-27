/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.buck.util.zip.Zip;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class FakeProjectFilesystemTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testFilesystemReturnsAddedContents() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("Some content", Paths.get("A.txt"));

    Optional<String> contents;
    contents = filesystem.readFileIfItExists(Paths.get("A.txt"));
    assertTrue("Fake file system must return added file contents.", contents.isPresent());
    assertEquals("Some content", contents.get());

    contents = filesystem.readFileIfItExists(Paths.get("B.txt"));
    assertFalse(
        "Fake file system must not return non-existing file contents", contents.isPresent());
  }

  @Test
  public void testReadLines() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath(linesToText("line one.", "line two.", ""), Paths.get("A.txt"));
    filesystem.writeLinesToPath(ImmutableList.of(), Paths.get("B.txt"));
    filesystem.writeContentsToPath(System.lineSeparator(), Paths.get("C.txt"));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of("line one.", "line two."), filesystem.readLines(Paths.get("A.txt")));

    MoreAsserts.assertIterablesEquals(ImmutableList.of(), filesystem.readLines(Paths.get("B.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(""), filesystem.readLines(Paths.get("C.txt")));

    MoreAsserts.assertIterablesEquals(ImmutableList.of(), filesystem.readLines(Paths.get("D.txt")));
  }

  @Test
  public void testTouch() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("A.txt"));
    filesystem.touch(Paths.get("A/B.txt"));

    assertTrue(filesystem.exists(Paths.get("A.txt")));
    assertTrue(filesystem.exists(Paths.get("A/B.txt")));
  }

  @Test
  public void testWalkRelativeFileTree() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("root/A.txt"));
    filesystem.touch(Paths.get("root/A/B/C.txt"));
    filesystem.touch(Paths.get("root/A/B.txt"));

    List<Path> filesVisited = new ArrayList<>();

    FileVisitor<Path> fileVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            filesVisited.add(path);
            return FileVisitResult.CONTINUE;
          }
        };

    filesystem.walkRelativeFileTree(Paths.get("root"), fileVisitor);
    assertThat(
        filesVisited,
        containsInAnyOrder(
            Paths.get("root/A.txt"), Paths.get("root/A/B/C.txt"), Paths.get("root/A/B.txt")));

    filesVisited.clear();
    filesystem.walkRelativeFileTree(Paths.get("root/A"), fileVisitor);
    assertThat(
        filesVisited, containsInAnyOrder(Paths.get("root/A/B/C.txt"), Paths.get("root/A/B.txt")));
  }

  @Test
  public void testWalkRelativeFileTreeWhenPathIsAFile() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("A.txt"));

    List<Path> filesVisited = new ArrayList<>();

    FileVisitor<Path> fileVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            filesVisited.add(path);
            return FileVisitResult.CONTINUE;
          }
        };

    filesystem.walkRelativeFileTree(Paths.get("A.txt"), fileVisitor);

    // Despite the awkward name, "contains" implies an exact match.
    assertThat(filesVisited, contains(Paths.get("A.txt")));
  }

  @Test
  public void testNewFileInputStream() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath("hello world", path);
    InputStreamReader reader =
        new InputStreamReader(filesystem.newFileInputStream(path), Charsets.UTF_8);
    String contents = CharStreams.toString(reader);
    assertEquals("hello world", contents);
  }

  @Test
  public void testAllExistingFileSystem() {
    AllExistingProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    assertTrue(filesystem.exists(Paths.get("somepath.txt")));
  }

  @Test
  public void testWriteContentsWithDefaultFileAttributes() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath("hello world", path);
    assertEquals(ImmutableSet.<FileAttribute<?>>of(), filesystem.getFileAttributesAtPath(path));
  }

  @Test
  public void testWriteContentsWithSpecifiedFileAttributes() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableSet<PosixFilePermission> permissions =
        ImmutableSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    FileAttribute<?> attribute = PosixFilePermissions.asFileAttribute(permissions);

    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath("hello world", Paths.get("hello.txt"), attribute);
    assertEquals(ImmutableSet.of(attribute), filesystem.getFileAttributesAtPath(path));
  }

  @Test
  public void testFileOutputStream() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path filePath = Paths.get("somefile.txt");

    try (OutputStream stream = filesystem.newFileOutputStream(filePath)) {
      stream.write("hello world".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals("hello world", filesystem.readFileIfItExists(filePath).get());
  }

  @Test
  public void testFlushThenCloseFileOutputStream() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path filePath = Paths.get("somefile.txt");
    OutputStream stream = filesystem.newFileOutputStream(filePath);
    stream.write("hello world".getBytes(StandardCharsets.UTF_8));
    stream.flush();
    stream.close();
    assertEquals("hello world", filesystem.readFileIfItExists(filePath).get());
  }

  @Test
  public void testSingleElementMkdirsExists() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo"));
    assertTrue(filesystem.isDirectory(Paths.get("foo")));
    assertFalse(filesystem.isDirectory(Paths.get("foo/bar")));
  }

  @Test
  public void testEachElementOfMkdirsExists() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo/bar/baz"));
    assertTrue(filesystem.isDirectory(Paths.get("foo")));
    assertTrue(filesystem.isDirectory(Paths.get("foo/bar")));
    assertTrue(filesystem.isDirectory(Paths.get("foo/bar/baz")));
    assertFalse(filesystem.isDirectory(Paths.get("foo/bar/baz/blech")));
  }

  @Test
  public void testDirectoryDoesNotExistAfterRmdir() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo"));
    filesystem.deleteRecursivelyIfExists(Paths.get("foo"));
    assertFalse(filesystem.isDirectory(Paths.get("foo")));
  }

  @Test
  public void testDirectoryExistsIfIsDirectory() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo"));
    assertTrue(filesystem.isDirectory(Paths.get("foo")));
    assertTrue(filesystem.exists(Paths.get("foo")));
  }

  @Test
  public void testIsFileAfterTouch() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("foo"));
    assertFalse(filesystem.isDirectory(Paths.get("foo")));
    assertTrue(filesystem.isFile(Paths.get("foo")));
    assertTrue(filesystem.exists(Paths.get("foo")));
  }

  @Test(expected = NoSuchFileException.class)
  public void fileModifiedTimeThrowsIfDoesNotExist() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.getLastModifiedTime(Paths.get("foo"));
  }

  @Test
  public void fileModifiedTimeIsSetOnInitialWrite() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(49152, 0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(clock);
    filesystem.touch(Paths.get("foo"));
    assertEquals(filesystem.getLastModifiedTime(Paths.get("foo")), FileTime.fromMillis(49152));
  }

  @Test
  public void fileModifiedTimeIsUpdatedOnSubsequentWrite() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(49152, 0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(clock);
    filesystem.touch(Paths.get("foo"));
    clock.setCurrentTimeMillis(64738);
    filesystem.touch(Paths.get("foo"));
    assertEquals(filesystem.getLastModifiedTime(Paths.get("foo")), FileTime.fromMillis(64738));
  }

  @Test
  public void fileModifiedTimeIsSetOnMkdirs() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(49152, 0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(clock);
    filesystem.mkdirs(Paths.get("foo/bar/baz"));
    assertEquals(filesystem.getLastModifiedTime(Paths.get("foo")), FileTime.fromMillis(49152));
    assertEquals(filesystem.getLastModifiedTime(Paths.get("foo/bar")), FileTime.fromMillis(49152));
    assertEquals(
        filesystem.getLastModifiedTime(Paths.get("foo/bar/baz")), FileTime.fromMillis(49152));
  }

  @Test
  public void testWritingAFileAddsParentDirectories() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("hello", Paths.get("test/one/two/three.txt"));

    assertTrue(filesystem.exists(Paths.get("test/one/two")));
    assertTrue(filesystem.exists(Paths.get("test/one")));
    assertTrue(filesystem.exists(Paths.get("test")));
  }

  @Test
  public void testIsSymLinkReturnsTrueForSymLink() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.createSymLink(Paths.get("foo"), Paths.get("bar"), false);
    assertTrue(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForFile() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("foo"));
    assertFalse(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForNotExistent() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    assertFalse(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testSortedDirectoryContents() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(clock);
    filesystem.mkdirs(Paths.get("foo"));
    Path a = Paths.get("foo/a.txt");
    filesystem.touch(a);
    clock.setCurrentTimeMillis(2000);
    Path b = Paths.get("foo/b.txt");
    filesystem.touch(b);
    clock.setCurrentTimeMillis(3000);
    Path c = Paths.get("foo/c.txt");
    filesystem.touch(c);
    filesystem.touch(Paths.get("foo/non-matching"));

    assertEquals(
        ImmutableSet.of(c, b, a),
        filesystem.getMtimeSortedMatchingDirectoryContents(Paths.get("foo"), "*.txt"));
  }

  @Test
  public void testGetFilesUnderPath() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path bar = Paths.get("foo/bar.txt");
    Path baz = Paths.get("foo/baz/blech.txt");
    filesystem.touch(bar);
    filesystem.touch(baz);

    assertEquals(ImmutableSet.of(bar, baz), filesystem.getFilesUnderPath(Paths.get("foo")));
  }

  @Test
  public void testDirectoryContentsAreInSortedOrder() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(clock);
    filesystem.touch(Paths.get("foo/foo"));
    filesystem.touch(Paths.get("foo/bar"));
    filesystem.touch(Paths.get("foo/baz"));

    assertThat(
        filesystem.getDirectoryContents(Paths.get("foo")),
        // Yes, contains() is an order-dependent matcher, despite its name.
        contains(Paths.get("foo/bar"), Paths.get("foo/baz"), Paths.get("foo/foo")));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/baz"));
    filesystem2.touch(Paths.get("foo/bar"));
    filesystem2.touch(Paths.get("foo/foo"));

    assertThat(
        filesystem2.getDirectoryContents(Paths.get("foo")),
        contains(Paths.get("foo/bar"), Paths.get("foo/baz"), Paths.get("foo/foo")));
  }

  @Test
  public void testCreateZip() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);

    Path file = Paths.get("file");
    filesystem.writeBytesToPath(contents, file);

    Path dir = Paths.get("dir");
    filesystem.mkdirs(dir);
    filesystem.writeBytesToPath(contents, dir.resolve("file"));

    Path output = tmp.newFile("output.zip");
    Zip.create(filesystem, ImmutableList.of(file, dir, dir.resolve("file")), output);

    try (ZipArchive zipArchive = new ZipArchive(output, /* forWriting */ false)) {
      assertEquals(ImmutableSet.of("", "dir/"), zipArchive.getDirNames());
      assertEquals(ImmutableSet.of("file", "dir/file"), zipArchive.getFileNames());
      assertArrayEquals(contents, zipArchive.readFully("file"));
      assertArrayEquals(contents, zipArchive.readFully("dir/file"));
    }
  }

  @Test
  public void filesystemWalkIsInSortedOrder() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.mkdirs(Paths.get("foo"));
    filesystem.touch(Paths.get("foo/bbbb"));
    filesystem.touch(Paths.get("foo/aaaa"));
    filesystem.mkdirs(Paths.get("bar"));
    filesystem.touch(Paths.get("bar/aaaa"));
    filesystem.touch(Paths.get("bar/cccc"));
    filesystem.touch(Paths.get("bar/bbbb"));

    AccumulatingFileVisitor visitor = new AccumulatingFileVisitor();
    filesystem.walkRelativeFileTree(Paths.get(""), visitor);
    assertThat(
        visitor.getSeen(),
        contains(
            Paths.get("bar/aaaa"),
            Paths.get("bar/bbbb"),
            Paths.get("bar/cccc"),
            Paths.get("foo/aaaa"),
            Paths.get("foo/bbbb")));
  }

  @Test
  public void testReadFileAttributes() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("foo"));
    filesystem.mkdirs(Paths.get("bar"));
    filesystem.touch(Paths.get("bar/aaaa"));

    BasicFileAttributes attrs;

    attrs = filesystem.readAttributes(Paths.get("foo"), BasicFileAttributes.class);
    assertFalse(attrs.isDirectory());
    assertTrue(attrs.isRegularFile());
    attrs = filesystem.readAttributes(Paths.get("bar"), BasicFileAttributes.class);
    assertTrue(attrs.isDirectory());
    assertFalse(attrs.isRegularFile());
    attrs = filesystem.readAttributes(Paths.get("bar/aaaa"), BasicFileAttributes.class);
    assertFalse(attrs.isDirectory());
    assertTrue(attrs.isRegularFile());
  }

  @Test(expected = IOException.class)
  public void testReadFileAttributesMissingFileThrows() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.readAttributes(Paths.get("foo"), BasicFileAttributes.class);
  }

  private static class AccumulatingFileVisitor implements FileVisitor<Path> {

    private final List<Path> seen = new ArrayList<>();

    public ImmutableList<Path> getSeen() {
      return ImmutableList.copyOf(seen);
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      seen.add(file);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      throw exc;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      if (exc != null) {
        throw exc;
      }
      return FileVisitResult.CONTINUE;
    }
  }
}
