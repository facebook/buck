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

package com.facebook.buck.io.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class MostFilesTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testDeleteRecursively() throws IOException {
    tmp.newFile(".dotfile");
    tmp.newFile("somefile");
    tmp.newFolder("foo");
    tmp.newFile("foo/bar");
    tmp.newFolder("foo/baz");
    tmp.newFile("foo/baz/biz");
    assertEquals("There should be files to delete.", 3, tmp.getRoot().toFile().listFiles().length);
    MostFiles.deleteRecursively(tmp.getRoot());
    assertNull(tmp.getRoot().toFile().listFiles());
  }

  @Test
  public void deleteRecursivelyIfExistsShouldNotFailOnNonExistentDir() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path fakeTmpDir = vfs.getPath("/tmp/fake-tmp-dir");
    MostFiles.deleteRecursivelyIfExists(fakeTmpDir.resolve("nonexistent"));
  }

  @Test
  public void deleteRecursivelyIfExistsDeletesDirectory() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path fakeTmpDir = vfs.getPath("/tmp/fake-tmp-dir");
    Path dirToDelete = fakeTmpDir.resolve("delete-me");
    Path childDir = dirToDelete.resolve("child-dir");
    Files.createDirectories(childDir);
    MostFiles.deleteRecursivelyIfExists(dirToDelete);
    assertThat(Files.exists(dirToDelete), is(false));
  }

  @Test
  public void deleteRecursivelyContentsOnlyLeavesParentDirectory() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path fakeTmpDir = vfs.getPath("/tmp/fake-tmp-dir");
    Path dirToDelete = fakeTmpDir.resolve("delete-me");
    Path childDir = dirToDelete.resolve("child-dir");
    Files.createDirectories(childDir);
    MostFiles.deleteRecursivelyWithOptions(
        dirToDelete, EnumSet.of(MostFiles.DeleteRecursivelyOptions.DELETE_CONTENTS_ONLY));
    assertThat(Files.exists(dirToDelete), is(true));
    assertThat(Files.exists(childDir), is(false));
  }

  @Test
  public void testWriteLinesToFile() throws IOException {
    Path outputFile = tmp.newFile("output.txt");
    ImmutableList<String> lines =
        ImmutableList.of("The", "quick brown fox", "jumps over", "the lazy dog.");
    MostFiles.writeLinesToFile(lines, outputFile);

    List<String> observedLines = Files.readAllLines(outputFile, Charsets.UTF_8);
    assertEquals(lines, observedLines);
  }

  @Test
  public void testSortFilesByAccessTime() throws IOException {
    Path dir = tmp.newFolder();
    Path fileW = dir.resolve("w");
    Path fileX = dir.resolve("x");
    Path fileY = dir.resolve("y");
    Path fileZ = dir.resolve("z");

    Files.write(fileW, "w".getBytes(UTF_8));
    Files.write(fileX, "x".getBytes(UTF_8));
    Files.write(fileY, "y".getBytes(UTF_8));
    Files.write(fileZ, "z".getBytes(UTF_8));

    Files.setAttribute(fileW, "lastAccessTime", FileTime.fromMillis(9000));
    Files.setAttribute(fileX, "lastAccessTime", FileTime.fromMillis(0));
    Files.setAttribute(fileY, "lastAccessTime", FileTime.fromMillis(1000));
    Files.setAttribute(fileZ, "lastAccessTime", FileTime.fromMillis(2000));

    File[] files = dir.toFile().listFiles();
    MostFiles.sortFilesByAccessTime(files);
    assertEquals(
        "Files short be sorted from most recently accessed to least recently accessed.",
        ImmutableList.of(fileW.toFile(), fileZ.toFile(), fileY.toFile(), fileX.toFile()),
        Arrays.asList(files));
  }

  @Test
  public void testMakeExecutable() throws IOException {
    Path file = tmp.newFile();

    // If the file system does not support the executable permission, skip the test
    assumeTrue(file.toFile().setExecutable(false));
    assertFalse("File should not be executable", file.toFile().canExecute());
    MostFiles.makeExecutable(file);
    assertTrue("File should be executable", file.toFile().canExecute());

    assumeTrue(file.toFile().setExecutable(true));
    assertTrue("File should be executable", Files.isExecutable(file));
    MostFiles.makeExecutable(file);
    assertTrue("File should be executable", Files.isExecutable(file));
  }

  @Test
  public void testMakeExecutableOnPosix() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

    Path file = tmp.newFile();

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "Owner's execute permission should have been set",
        "r-x------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---r-----"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "Group's execute permission should have been set",
        "---r-x---",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("------r--"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "Others' execute permission should have been set",
        "------r-x",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "All execute permissions should have been set",
        "r-xr-xr-x",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xrw-rwx"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "Only group's execute permission should have been set",
        "r-xrwxrwx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w---x-wx"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "-w---x-wx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "---------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxrwxrwx"));
    MostFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "rwxrwxrwx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test
  public void concatenatingNoFilesReturnsFalse() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path outputPath = vfs.getPath("logs.txt");
    boolean collected = MostFiles.concatenateFiles(outputPath, ImmutableList.of());
    assertThat(collected, is(false));
    assertThat(Files.exists(outputPath), is(false));
  }

  @Test
  public void concatenatingTwoEmptyFilesReturnsFalse() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path fooPath = vfs.getPath("foo.txt");
    Files.write(fooPath, new byte[0]);

    Path barPath = vfs.getPath("bar.txt");
    Files.write(barPath, new byte[0]);

    Path outputPath = vfs.getPath("logs.txt");

    boolean concatenated =
        MostFiles.concatenateFiles(outputPath, ImmutableList.of(fooPath, barPath));
    assertThat(concatenated, is(false));

    assertThat(Files.exists(outputPath), is(false));
  }

  @Test
  public void concatenatingTwoNonEmptyFilesReturnsTrueAndWritesConcatenatedFile() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path fooPath = vfs.getPath("foo.txt");
    Files.write(fooPath, "hello world\n".getBytes(UTF_8));

    Path barPath = vfs.getPath("bar.txt");
    Files.write(barPath, "goodbye world\n".getBytes(UTF_8));

    Path outputPath = vfs.getPath("logs.txt");

    boolean concatenated =
        MostFiles.concatenateFiles(outputPath, ImmutableList.of(fooPath, barPath));
    assertThat(concatenated, is(true));

    assertThat(
        Files.readAllLines(outputPath, UTF_8),
        Matchers.equalTo(ImmutableList.of("hello world", "goodbye world")));
  }
}
