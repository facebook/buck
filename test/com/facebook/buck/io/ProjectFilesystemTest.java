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

package com.facebook.buck.io;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem.CopySourceMode;
import com.facebook.buck.testutil.WatchEvents;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unit test for {@link com.facebook.buck.io.ProjectFilesystem}. */
public class ProjectFilesystemTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testIsFile() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");

    assertTrue(filesystem.isFile(Paths.get("foo/bar.txt")));
    assertFalse(filesystem.isFile(Paths.get("i_do_not_exist")));
    assertFalse(
        "foo/ is a directory, but not an ordinary file",
        filesystem.isFile(Paths.get("foo")));
  }

  @Test
  public void testSetLastModifiedTime() throws IOException {
    Path path = tmp.newFile("somefile");
    filesystem.setLastModifiedTime(path, FileTime.fromMillis(0));
    assertEquals(Files.getLastModifiedTime(path).toMillis(), 0);
  }

  @Test
  public void testIsDirectory() throws IOException {
    Path dir = tmp.newFolder("src");
    Path file = tmp.newFile("BUCK");
    assertTrue(filesystem.isDirectory(dir));
    assertFalse(filesystem.isDirectory(file));
  }

  @Test
  public void testMkdirsCanCreateNestedFolders() throws IOException {
    filesystem.mkdirs(new File("foo/bar/baz").toPath());
    assertTrue(Files.isDirectory(tmp.getRoot().resolve("foo/bar/baz")));
  }

  @Test
  public void testCreateNewFile() throws IOException {
    Path path = Paths.get("somefile");
    filesystem.createNewFile(path);
    assertTrue(Files.exists(tmp.getRoot().toAbsolutePath().resolve(path)));
  }

  @Test
  public void testCreateParentDirs() throws IOException {
    Path pathRelativeToProjectRoot = Paths.get("foo/bar/baz.txt");
    filesystem.createParentDirs(pathRelativeToProjectRoot);
    assertTrue(Files.isDirectory(tmp.getRoot().resolve("foo")));
    assertTrue(Files.isDirectory(tmp.getRoot().resolve("foo/bar")));
    assertFalse(
        "createParentDirs() should create directories, but not the leaf/file part of the path.",
        Files.exists(tmp.getRoot().resolve("foo/bar/baz.txt")));
  }

  @Test(expected = NullPointerException.class)
  public void testReadFirstLineRejectsNullString() {
    filesystem.readFirstLine(/* pathRelativeToProjectRoot */ (String) null);
  }

  @Test(expected = NullPointerException.class)
  public void testReadFirstLineRejectsNullPath() {
    filesystem.readFirstLine(/* pathRelativeToProjectRoot */ (Path) null);
  }

  @Test
  public void testReadFirstLineToleratesNonExistentFile() {
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineWithEmptyFile() throws IOException {
    Path emptyFile = tmp.newFile("foo.txt");
    Files.write(emptyFile, new byte[0]);
    assertTrue(Files.isRegularFile(emptyFile));
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineFromMultiLineFile() throws IOException {
    Path multiLineFile = tmp.newFile("foo.txt");
    Files.write(multiLineFile, "foo\nbar\nbaz\n".getBytes(UTF_8));
    assertEquals(Optional.of("foo"), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void getReaderIfFileExists() throws IOException {
    Path file = tmp.newFile("foo.txt");
    Files.write(file, "fooooo\nbar\nbaz\n".getBytes(UTF_8));
    assertEquals(
        "fooooo\nbar\nbaz\n",
        CharStreams.toString(filesystem.getReaderIfFileExists(Paths.get("foo.txt")).get()));
  }

  @Test
  public void getReaderIfFileExistsNoFile() throws IOException {
    assertEquals(Optional.absent(), filesystem.getReaderIfFileExists(Paths.get("foo.txt")));
  }

  @Test
  public void testGetFileSize() throws IOException {
    Path wordsFile = tmp.newFile("words.txt");
    String content = "Here\nare\nsome\nwords.\n";
    Files.write(wordsFile, content.getBytes(UTF_8));

    assertEquals(content.length(), filesystem.getFileSize(Paths.get("words.txt")));
  }

  @Test(expected = IOException.class)
  public void testGetFileSizeThrowsForNonExistentFile() throws IOException {
    filesystem.getFileSize(Paths.get("words.txt"));
  }

  @Test
  public void testWriteLinesToPath() throws IOException {
    Iterable<String> lines = ImmutableList.of("foo", "bar", "baz");
    filesystem.writeLinesToPath(lines, Paths.get("lines.txt"));

    String contents = new String(Files.readAllBytes(tmp.getRoot().resolve("lines.txt")), UTF_8);
    assertEquals("foo\nbar\nbaz\n", contents);
  }

  @Test
  public void testWriteBytesToPath() throws IOException {
    String content = "Hello, World!";
    byte[] bytes = content.getBytes();
    filesystem.writeBytesToPath(bytes, Paths.get("hello.txt"));
    assertEquals(
        content,
        new String(Files.readAllBytes(tmp.getRoot().resolve("hello.txt")), UTF_8));
  }

  @Test
  public void testCopyToPath() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("Hello, world!".getBytes());
    filesystem.copyToPath(inputStream, Paths.get("bytes.txt"));

    assertEquals(
        "The bytes on disk should match those from the InputStream.",
        "Hello, world!",
        new String(Files.readAllBytes(tmp.getRoot().resolve("bytes.txt")), UTF_8));
  }

  @Test
  public void testCopyToPathWithOptions() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("hello!".getBytes());
    filesystem.copyToPath(inputStream, Paths.get("replace_me.txt"));

    inputStream = new ByteArrayInputStream("hello again!".getBytes());
    filesystem.copyToPath(
        inputStream,
        Paths.get("replace_me.txt"),
        StandardCopyOption.REPLACE_EXISTING);

    assertEquals(
        "The bytes on disk should match those from the second InputStream.",
        "hello again!",
        new String(Files.readAllBytes(tmp.getRoot().resolve("replace_me.txt")), UTF_8));
  }

  @Test
  public void testCopyFolder() throws IOException {
    // Build up a directory of dummy files.
    tmp.newFolder("src");
    tmp.newFolder("src/com");
    tmp.newFolder("src/com/example");
    tmp.newFolder("src/com/example/foo");
    tmp.newFile("src/com/example/foo/Foo.java");
    tmp.newFile("src/com/example/foo/package.html");
    tmp.newFolder("src/com/example/bar");
    tmp.newFile("src/com/example/bar/Bar.java");
    tmp.newFile("src/com/example/bar/package.html");

    // Copy the contents of src/ to dest/.
    tmp.newFolder("dest");
    filesystem.copyFolder(Paths.get("src"), Paths.get("dest"));

    assertTrue(Files.exists(tmp.getRoot().resolve("dest/com/example/foo/Foo.java")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/com/example/foo/package.html")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/com/example/bar/Bar.java")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/com/example/bar/package.html")));
  }

  @Test
  public void testCopyFolderAndContents() throws IOException {
    // Build up a directory of dummy files.
    tmp.newFolder("src");
    tmp.newFolder("src/com");
    tmp.newFolder("src/com/example");
    tmp.newFolder("src/com/example/foo");
    tmp.newFile("src/com/example/foo/Foo.java");
    tmp.newFile("src/com/example/foo/package.html");
    tmp.newFolder("src/com/example/bar");
    tmp.newFile("src/com/example/bar/Bar.java");
    tmp.newFile("src/com/example/bar/package.html");

    // Copy the contents of src/ to dest/ (including src itself).
    tmp.newFolder("dest");
    filesystem.copy(Paths.get("src"), Paths.get("dest"), CopySourceMode.DIRECTORY_AND_CONTENTS);

    assertTrue(Files.exists(tmp.getRoot().resolve("dest/src/com/example/foo/Foo.java")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/src/com/example/foo/package.html")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/src/com/example/bar/Bar.java")));
    assertTrue(Files.exists(tmp.getRoot().resolve("dest/src/com/example/bar/package.html")));
  }

  @Test
  public void testCopyFile() throws IOException {
    tmp.newFolder("foo");
    Path file = tmp.newFile("foo/bar.txt");
    String content = "Hello, World!";
    Files.write(file, content.getBytes(UTF_8));

    filesystem.copyFile(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt"));
    assertEquals(
        content,
        new String(Files.readAllBytes(tmp.getRoot().resolve("foo/baz.txt")), UTF_8));
  }

  @Test
  public void testDeleteFileAtPath() throws IOException {
    Path path = Paths.get("foo.txt");
    Path file = tmp.newFile(path.toString());
    assertTrue(Files.exists(file));
    filesystem.deleteFileAtPath(path);
    assertFalse(Files.exists(file));
  }

  @Test
  public void testCreateContextStringForModifyEvent() throws IOException {
    Path file = tmp.newFile("foo.txt");
    WatchEvent<Path> modifyEvent = WatchEvents.createPathEvent(
        file,
        StandardWatchEventKinds.ENTRY_MODIFY);
    assertEquals(
        file.toAbsolutePath().toString(),
        filesystem.createContextString(modifyEvent));
  }

  @Test
  public void testCreateContextStringForOverflowEvent() {
    WatchEvent<Object> overflowEvent = new WatchEvent<Object>() {
      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 0;
      }

      @Override
      public Object context() {
        return new Object() {
          @Override
          public String toString() {
            return "I am the context string.";
          }
        };
      }
    };
    assertEquals("I am the context string.", filesystem.createContextString(overflowEvent));
  }

  @Test
  public void testWalkFileTreeWhenProjectRootIsNotWorkingDir() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");

    final ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    filesystem.walkRelativeFileTree(
        Paths.get("dir"), new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames.build(), containsInAnyOrder("file.txt", "file2.txt"));
  }

  @Test
  public void testWalkFileTreeWhenProjectRootIsWorkingDir() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get("."));
    final ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    Path pathRelativeToProjectRoot = Paths.get(
        "test/com/facebook/buck/io/testdata/directory_traversal_ignore_paths");
    projectFilesystem.walkRelativeFileTree(
        pathRelativeToProjectRoot,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(
        fileNames.build(), containsInAnyOrder(
            "file",
            "a_file",
            "b_file",
            "b_c_file",
            "b_d_file"));
  }

  @Test
  public void testWalkFileTreeFollowsSymlinks() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    Files.createSymbolicLink(
        tmp.getRoot().resolve("linkdir"),
        tmp.getRoot().resolve("dir"));

    final ImmutableList.Builder<Path> filePaths = ImmutableList.builder();

    filesystem.walkRelativeFileTree(
        Paths.get(""), new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            filePaths.add(file);
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(
        filePaths.build(),
        containsInAnyOrder(Paths.get("dir/file.txt"), Paths.get("linkdir/file.txt")));
  }

  @Test
  public void whenContextNullThenCreateContextStringReturnsValidString() {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get("."));
    assertThat(
        "Context string should contain null.",
        projectFilesystem.createContextString(WatchEvents.createOverflowEvent()),
        Matchers.containsString("null"));
  }

  @Test
  public void testGetFilesUnderPath() throws IOException {
    tmp.newFile("file1");
    tmp.newFolder("dir1");
    tmp.newFile("dir1/file2");
    tmp.newFolder("dir1/dir2");
    tmp.newFile("dir1/dir2/file3");

    assertThat(
        filesystem.getFilesUnderPath(
            Paths.get("dir1"),
            Predicates.<Path>alwaysTrue(),
            EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(
            Paths.get("dir1"),
            Predicates.equalTo(Paths.get("dir1/dir2/file3")),
            EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(Paths.get("dir1")),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(
            Paths.get("dir1"),
            Predicates.equalTo(Paths.get("dir1/file2"))),
        containsInAnyOrder(Paths.get("dir1/file2")));
  }

  @Test
  public void testCreateZipPreservesExecutablePermissions() throws IOException {

    // Create a empty executable file.
    Path exe = tmp.newFile("test.exe");
    MoreFiles.makeExecutable(exe);

    // Archive it into a zipfile using `ProjectFileSystem.createZip`.
    Path zipFile = tmp.getRoot().resolve("test.zip");
    filesystem.createZip(ImmutableList.of(exe), zipFile);

    // Now unpack the archive (using apache's common-compress, as it preserves
    // executable permissions) and verify that the archive entry has executable
    // permissions.
    try (ZipFile zip = new ZipFile(zipFile.toFile())) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      assertTrue(entries.hasMoreElements());
      ZipArchiveEntry entry = entries.nextElement();
      Set<PosixFilePermission> permissions =
          MorePosixFilePermissions.fromMode(entry.getExternalAttributes() >> 16);
      assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
      assertFalse(entries.hasMoreElements());
    }

  }

  @Test
  public void testCreateZipIgnoresMtimes() throws IOException {

    // Create a empty executable file.
    Path exe = tmp.newFile("foo");

    // Archive it into a zipfile using `ProjectFileSystem.createZip`.
    Path zipFile = tmp.getRoot().resolve("test.zip");
    filesystem.createZip(
        ImmutableList.of(exe),
        zipFile,
        ImmutableMap.of(Paths.get("additional"), "info"));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_EPOCH_START));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(zipFile))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
  }

  @Test
  public void testCreateReadOnlyFileSetsPermissions() throws IOException {
    Path path = Paths.get("hello.txt");
    ImmutableSet<PosixFilePermission> permissions =
      ImmutableSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

    filesystem.writeContentsToPath(
        "hello world",
        path,
        PosixFilePermissions.asFileAttribute(permissions));
    // The umask may restrict the actual permissions on the filesystem:
    // https://fburl.com/26569549
    // So the best we can do is to check that the actual permissions are a
    // strict subset of the expected permissions.
    PosixFileAttributes attrs = filesystem.readAttributes(path, PosixFileAttributes.class);
    assertTrue(permissions.containsAll(attrs.permissions()));
  }


  @Test
  public void testCreateZip() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");
    tmp.newFile("foo/baz.txt");

    Path output = tmp.newFile("out.zip");

    filesystem.createZip(
        ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt")),
        output);

    ZipInspector zipInspector = new ZipInspector(output);
    assertEquals(
        ImmutableSet.of("foo/bar.txt", "foo/baz.txt"),
        zipInspector.getZipFileEntries());
  }

  @Test
  public void testCreateZipWithEmptyDir() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");
    tmp.newFile("foo/baz.txt");
    tmp.newFolder("empty");

    Path output = tmp.newFile("out.zip");

    filesystem.createZip(
        ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt"), Paths.get("empty")),
        output);

    ZipInspector zipInspector = new ZipInspector(output);
    assertEquals(
        ImmutableSet.of("foo/bar.txt", "foo/baz.txt", "empty/"),
        zipInspector.getZipFileEntries());
  }

  @Test
  public void testCreateZipWithAdditionalFiles() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");
    tmp.newFile("foo/baz.txt");

    Path output = tmp.newFile("out.zip");

    filesystem.createZip(
        ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt")),
        output,
        ImmutableMap.of(Paths.get("log/info.txt"), "hello"));

    ZipInspector zipInspector = new ZipInspector(output);
    assertEquals(
        ImmutableSet.of("foo/bar.txt", "foo/baz.txt", "log/info.txt"),
        zipInspector.getZipFileEntries());
  }

  @Test
  public void testIsSymLinkReturnsTrueForSymLink() throws IOException {
    Path rootPath = tmp.getRoot();
    Files.createSymbolicLink(rootPath.resolve("foo"), rootPath.resolve("bar"));
    assertTrue(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForFile() throws IOException {
    tmp.newFile("foo");
    assertFalse(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForNotExistent() throws IOException {
    assertFalse(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testSortedDirectoryContents() throws IOException {
    tmp.newFolder("foo");
    Path a = tmp.newFile("foo/a.txt");
    Files.setLastModifiedTime(a, FileTime.fromMillis(1000));
    Path b = tmp.newFile("foo/b.txt");
    Files.setLastModifiedTime(b, FileTime.fromMillis(2000));
    Path c = tmp.newFile("foo/c.txt");
    Files.setLastModifiedTime(c, FileTime.fromMillis(3000));
    tmp.newFile("foo/non-matching");

    assertEquals(
        ImmutableSet.of(c, b, a),
        filesystem.getSortedMatchingDirectoryContents(
            Paths.get("foo"),
            "*.txt"));
  }
}
