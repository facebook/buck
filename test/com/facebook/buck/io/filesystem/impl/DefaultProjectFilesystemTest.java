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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unarchive.Unzip;
import com.facebook.buck.util.zip.Zip;
import com.facebook.buck.util.zip.ZipConstants;
import com.google.common.base.Charsets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultProjectFilesystemTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expected = ExpectedException.none();

  private DefaultProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void asViewCreatesEmptyViewAtRoot() {
    DefaultProjectFilesystemView view = filesystem.asView();

    // view should be relative to root
    assertEquals(Paths.get(""), view.projectRoot);
    assertEquals(filesystem.getRootPath(), view.getRootPath());
    assertEquals(Paths.get(""), view.relativize(tmp.getRoot()));

    // view should have no ignores
    assertTrue(view.ignoredPaths.isEmpty());
  }

  @Test
  public void testIsFile() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");

    assertTrue(filesystem.isFile(Paths.get("foo/bar.txt")));
    assertFalse(filesystem.isFile(Paths.get("i_do_not_exist")));
    assertFalse(
        "foo/ is a directory, but not an ordinary file", filesystem.isFile(Paths.get("foo")));
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
  public void mkdirsAllowsSymlinksToDirs() throws IOException {
    Assume.assumeTrue("System supports symlinks", !Platform.detect().equals(Platform.WINDOWS));
    Files.createDirectory(tmp.getRoot().resolve("real_dir"));
    // Create a relative symbolic link.
    CreateSymlinksForTests.createSymLink(
        tmp.getRoot().resolve("symlinked_dir"), filesystem.getPath("real_dir"));
    filesystem.mkdirs(filesystem.getPath("symlinked_dir"));
  }

  @Test
  public void testSymlinkForceCanDeleteDirectory() throws IOException {
    Path realFileDir = Files.createDirectory(tmp.getRoot().resolve("realfile"));
    Files.createFile(realFileDir.resolve("file"));
    Files.createFile(realFileDir.resolve("file2"));
    Path symlinkDir = Files.createDirectory(tmp.getRoot().resolve("symlink"));
    Files.createFile(symlinkDir.resolve("junk"));

    filesystem.createSymLink(symlinkDir, realFileDir, true);

    try (Stream<Path> paths = Files.list(symlinkDir)) {
      List<Path> filesFound = paths.collect(Collectors.toList());
      assertThat(
          filesFound, containsInAnyOrder(symlinkDir.resolve("file"), symlinkDir.resolve("file2")));
    }
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
    assertEquals(Optional.empty(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineWithEmptyFile() throws IOException {
    Path emptyFile = tmp.newFile("foo.txt");
    Files.write(emptyFile, new byte[0]);
    assertTrue(Files.isRegularFile(emptyFile));
    assertEquals(Optional.empty(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineFromMultiLineFile() throws IOException {
    Path multiLineFile = tmp.newFile("foo.txt");
    Files.write(multiLineFile, "foo\nbar\nbaz\n".getBytes(UTF_8));
    assertEquals(Optional.of("foo"), filesystem.readFirstLine("foo.txt"));
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
    byte[] bytes = content.getBytes(UTF_8);
    filesystem.writeBytesToPath(bytes, Paths.get("hello.txt"));
    assertEquals(
        content, new String(Files.readAllBytes(tmp.getRoot().resolve("hello.txt")), UTF_8));
  }

  @Test
  public void testCopyToPath() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("Hello, world!".getBytes(UTF_8));
    filesystem.copyToPath(inputStream, Paths.get("bytes.txt"));

    assertEquals(
        "The bytes on disk should match those from the InputStream.",
        "Hello, world!",
        new String(Files.readAllBytes(tmp.getRoot().resolve("bytes.txt")), UTF_8));
  }

  @Test
  public void testCopyToPathWithOptions() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("hello!".getBytes(UTF_8));
    filesystem.copyToPath(inputStream, Paths.get("replace_me.txt"));

    inputStream = new ByteArrayInputStream("hello again!".getBytes(UTF_8));
    filesystem.copyToPath(
        inputStream, Paths.get("replace_me.txt"), StandardCopyOption.REPLACE_EXISTING);

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
        content, new String(Files.readAllBytes(tmp.getRoot().resolve("foo/baz.txt")), UTF_8));
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
  public void testWalkFileTreeWhenProjectRootIsNotWorkingDir() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");

    ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    filesystem.walkRelativeFileTree(
        Paths.get("dir"),
        new SimpleFileVisitor<Path>() {
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
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    Path pathRelativeToProjectRoot =
        Paths.get("test/com/facebook/buck/io/testdata/directory_traversal_ignore_paths");
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
        fileNames.build(), containsInAnyOrder("file", "a_file", "b_file", "b_c_file", "b_d_file"));
  }

  @Test
  public void testWalkFileTreeFollowsSymlinks() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    CreateSymlinksForTests.createSymLink(
        tmp.getRoot().resolve("linkdir"), tmp.getRoot().resolve("dir"));

    ImmutableList.Builder<Path> filePaths = ImmutableList.builder();

    filesystem.walkRelativeFileTree(
        Paths.get(""),
        new SimpleFileVisitor<Path>() {
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
  public void testGetFilesUnderPath() throws IOException {
    tmp.newFile("file1");
    tmp.newFolder("dir1");
    tmp.newFile("dir1/file2");
    tmp.newFolder("dir1/dir2");
    tmp.newFile("dir1/dir2/file3");

    assertThat(
        filesystem.getFilesUnderPath(
            Paths.get("dir1"), x -> true, EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(
            Paths.get("dir1"),
            Paths.get("dir1/dir2/file3")::equals,
            EnumSet.noneOf(FileVisitOption.class)),
        containsInAnyOrder(Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(Paths.get("dir1")),
        containsInAnyOrder(Paths.get("dir1/file2"), Paths.get("dir1/dir2/file3")));

    assertThat(
        filesystem.getFilesUnderPath(Paths.get("dir1"), Paths.get("dir1/file2")::equals),
        containsInAnyOrder(Paths.get("dir1/file2")));
  }

  @Test
  public void testCreateZipPreservesExecutablePermissions() throws IOException {

    // Create a empty executable file.
    Path exe = tmp.newFile("test.exe");
    MostFiles.makeExecutable(exe);

    // Archive it into a zipfile using `Zip.create`.
    Path zipFile = tmp.getRoot().resolve("test.zip");
    Zip.create(filesystem, ImmutableList.of(exe), zipFile);

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

    // Archive it into a zipfile using `Zip.create`.
    Path zipFile = tmp.getRoot().resolve("test.zip");
    Zip.create(filesystem, ImmutableList.of(exe), zipFile);

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(zipFile))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
  }

  @Test
  public void testCreateReadOnlyFileSetsPermissions() throws IOException {
    Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path path = Paths.get("hello.txt");
    ImmutableSet<PosixFilePermission> permissions =
        ImmutableSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    filesystem.writeContentsToPath(
        "hello world", path, PosixFilePermissions.asFileAttribute(permissions));
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

    Zip.create(
        filesystem, ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt")), output);

    ZipInspector zipInspector = new ZipInspector(output);
    assertEquals(ImmutableSet.of("foo/bar.txt", "foo/baz.txt"), zipInspector.getZipFileEntries());
  }

  @Test
  public void testCreateZipWithEmptyDir() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");
    tmp.newFile("foo/baz.txt");
    tmp.newFolder("empty");

    Path output = tmp.newFile("out.zip");

    Zip.create(
        filesystem,
        ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt"), Paths.get("empty")),
        output);

    ZipInspector zipInspector = new ZipInspector(output);
    assertEquals(
        ImmutableSet.of("foo/bar.txt", "foo/baz.txt", "empty/"), zipInspector.getZipFileEntries());
  }

  @Test
  public void testGetZipContents() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");
    tmp.newFile("foo/baz.txt");

    Path output = tmp.newFile("out.zip");

    Zip.create(
        filesystem, ImmutableList.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt")), output);

    ImmutableCollection<Path> actualContents =
        ImmutableSortedSet.copyOf(Unzip.getZipMembers(filesystem.resolve(output)));
    assertEquals(
        ImmutableSortedSet.of(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt")), actualContents);
  }

  @Test
  public void testIsSymLinkReturnsTrueForSymLink() throws IOException {
    Path rootPath = tmp.getRoot();
    CreateSymlinksForTests.createSymLink(rootPath.resolve("foo"), rootPath.resolve("bar"));
    assertTrue(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForFile() throws IOException {
    tmp.newFile("foo");
    assertFalse(filesystem.isSymLink(Paths.get("foo")));
  }

  @Test
  public void testIsSymLinkReturnsFalseForNotExistent() {
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
        filesystem.getMtimeSortedMatchingDirectoryContents(Paths.get("foo"), "*.txt"));
  }

  @Test
  public void testExtractIgnorePaths() throws InterruptedException {
    Config config =
        ConfigBuilder.createFromText("[project]", "ignore = .git, foo, bar/, baz//, a/b/c");
    Path rootPath = tmp.getRoot();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(rootPath, config);
    ImmutableSet<Path> ignorePaths =
        FluentIterable.from(filesystem.getIgnorePaths())
            .filter(input -> input.getType() == PathOrGlobMatcher.Type.PATH)
            .transform(PathOrGlobMatcher::getPath)
            .toSet();
    assertThat(
        ImmutableSortedSet.copyOf(Ordering.natural(), ignorePaths),
        equalTo(
            ImmutableSortedSet.of(
                filesystem.getBuckPaths().getBuckOut(),
                filesystem.getBuckPaths().getTrashDir(),
                Paths.get(".idea"),
                Paths.get(
                    System.getProperty(
                        DefaultProjectFilesystemFactory.BUCK_BUCKD_DIR_KEY, ".buckd")),
                filesystem.getBuckPaths().getCacheDir(),
                Paths.get(".git"),
                Paths.get("foo"),
                Paths.get("bar"),
                Paths.get("baz"),
                Paths.get("a/b/c"))));
  }

  @Test
  public void testExtractIgnorePathsWithCacheDir() throws InterruptedException {
    Config config = ConfigBuilder.createFromText("[cache]", "dir = cache_dir");
    Path rootPath = tmp.getRoot();
    ImmutableSet<Path> ignorePaths =
        FluentIterable.from(
                TestProjectFilesystems.createProjectFilesystem(rootPath, config).getIgnorePaths())
            .filter(input -> input.getType() == PathOrGlobMatcher.Type.PATH)
            .transform(PathOrGlobMatcher::getPath)
            .toSet();
    assertThat(
        "Cache directory should be in set of ignored paths",
        ignorePaths,
        Matchers.hasItem(Paths.get("cache_dir")));
  }

  @Test
  public void ignoredPathsShouldBeIgnoredWhenWalkingTheFilesystem()
      throws InterruptedException, IOException {
    Config config = ConfigBuilder.createFromText("[project]", "ignore = **/*.orig");

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot(), config);
    Files.createDirectories(tmp.getRoot().resolve("foo/bar"));
    filesystem.touch(Paths.get("foo/bar/cake.txt"));
    filesystem.touch(Paths.get("foo/bar/cake.txt.orig"));

    ImmutableSet.Builder<String> allPaths = ImmutableSet.builder();

    filesystem.walkRelativeFileTree(
        tmp.getRoot(),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            allPaths.add(file.toString());
            return FileVisitResult.CONTINUE;
          }
        });

    ImmutableSet<String> found = allPaths.build();
    assertTrue(found.contains(Paths.get("foo/bar/cake.txt").toString()));
    assertFalse(found.contains(Paths.get("foo/bar/cake.txt.orig").toString()));
  }

  @Test
  public void twoProjectFilesystemsWithSameIgnoreGlobsShouldBeEqual() throws InterruptedException {
    Config config = ConfigBuilder.createFromText("[project]", "ignore = **/*.orig");
    Path rootPath = tmp.getRoot();
    ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();
    assertThat(
        "Two ProjectFilesystems with same glob in ignore should be equal",
        projectFilesystemFactory.createProjectFilesystem(rootPath, config),
        equalTo(projectFilesystemFactory.createProjectFilesystem(rootPath, config)));
  }

  @Test
  public void getPathReturnsPathWithCorrectFilesystem() throws InterruptedException, IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path root = vfs.getPath("/root");
    Files.createDirectories(root);
    ProjectFilesystem projectFilesystem =
        new DefaultProjectFilesystemFactory().createProjectFilesystem(root);
    assertEquals(vfs, projectFilesystem.getPath("bar").getFileSystem());
    assertEquals(vfs.getPath("bar"), projectFilesystem.getPath("bar"));
  }

  @Test
  public void moveChildrenFailsIfFilesAlreadyExistAndOverwriteIsNotSet() throws IOException {
    expected.expect(IOException.class);

    tmp.newFolder("dir1");
    Files.write(tmp.newFile("dir1/file1"), "new file 1".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir2");
    tmp.newFile("dir2/file1");

    filesystem.mergeChildren(Paths.get("dir1"), Paths.get("dir2"));
  }

  @Test
  public void moveChildrenFailsIfTryingToMoveAFileToANonEmptyDirectory() throws IOException {
    expected.expect(IOException.class);

    tmp.newFolder("dir1");
    Files.write(tmp.newFile("dir1/file1"), "new file 1".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir2");
    tmp.newFolder("dir2/file1");
    tmp.newFile("dir2/file1/file2");

    filesystem.mergeChildren(
        Paths.get("dir1"), Paths.get("dir2"), StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  public void moveChildrenMergesOneDirectoryIntoAnother() throws IOException {
    Path srcDir = tmp.newFolder("dir1");
    Files.write(tmp.newFile("dir1/file1"), "new file 1".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir1/subdir1");
    Files.write(tmp.newFile("dir1/subdir1/file2"), "new file 2".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir1/subdir1/subdir2");
    Files.write(tmp.newFile("dir1/subdir1/subdir2/file3"), "new file 3".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir1/subdir1/subdir2/subdir3");

    tmp.newFolder("dir2");
    Path destRoot = tmp.newFolder("dir2/dir3");
    Files.write(tmp.newFile("dir2/dir3/file1"), "old file 1".getBytes(Charsets.UTF_8));
    Files.write(tmp.newFile("dir2/dir3/file1_1"), "old file 1_1".getBytes(Charsets.UTF_8));

    tmp.newFolder("dir2/dir3/dir4");
    tmp.newFolder("dir2/dir3/subdir1");

    filesystem.mergeChildren(
        Paths.get("dir1"), Paths.get("dir2/dir3"), StandardCopyOption.REPLACE_EXISTING);

    assertTrue(Files.isDirectory(srcDir));
    assertEquals("new file 1", Files.readAllLines(destRoot.resolve("file1")).get(0));
    assertEquals("old file 1_1", Files.readAllLines(destRoot.resolve("file1_1")).get(0));

    assertTrue(Files.isDirectory(destRoot.resolve("dir4")));
    assertTrue(Files.isDirectory(destRoot.resolve("subdir1")));
    assertEquals(
        "new file 2", Files.readAllLines(destRoot.resolve("subdir1").resolve("file2")).get(0));

    assertTrue(Files.isDirectory(destRoot.resolve("subdir1").resolve("subdir2")));
    assertEquals(
        "new file 3",
        Files.readAllLines(destRoot.resolve("subdir1").resolve("subdir2").resolve("file3")).get(0));

    assertTrue(
        Files.isDirectory(destRoot.resolve("subdir1").resolve("subdir2").resolve("subdir3")));

    assertFalse(Files.exists(srcDir.resolve("subdir1")));
    assertFalse(Files.exists(srcDir.resolve("file1")));
  }
}
