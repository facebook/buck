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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

public class MoreFilesTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testDeleteRecursively() throws IOException {
    tmp.newFile(".dotfile");
    tmp.newFile("somefile");
    tmp.newFolder("foo");
    tmp.newFile("foo/bar");
    tmp.newFolder("foo/baz");
    tmp.newFile("foo/baz/biz");
    assertEquals("There should be files to delete.", 3, tmp.getRoot().listFiles().length);
    MoreFiles.deleteRecursively(tmp.getRoot().toPath());
    assertNull(tmp.getRoot().listFiles());
  }

  @Test
  public void testWriteLinesToFile() throws IOException {
    File outputFile = tmp.newFile("output.txt");
    ImmutableList<String> lines = ImmutableList.of(
        "The",
        "quick brown fox",
        "jumps over",
        "the lazy dog.");
    MoreFiles.writeLinesToFile(lines, outputFile);

    List<String> observedLines = Files.readLines(outputFile, Charsets.UTF_8);
    assertEquals(lines, observedLines);
  }

  @Test
  public void testSortFilesByAccessTime() throws IOException {
    File dir = tmp.newFolder();
    File fileW = new File(dir, "w");
    File fileX = new File(dir, "x");
    File fileY = new File(dir, "y");
    File fileZ = new File(dir, "z");

    Files.write("w", fileW, Charsets.UTF_8);
    Files.write("x", fileX, Charsets.UTF_8);
    Files.write("y", fileY, Charsets.UTF_8);
    Files.write("z", fileZ, Charsets.UTF_8);

    java.nio.file.Files.setAttribute(fileW.toPath(), "lastAccessTime", FileTime.fromMillis(9000));
    java.nio.file.Files.setAttribute(fileX.toPath(), "lastAccessTime", FileTime.fromMillis(0));
    java.nio.file.Files.setAttribute(fileY.toPath(), "lastAccessTime", FileTime.fromMillis(1000));
    java.nio.file.Files.setAttribute(fileZ.toPath(), "lastAccessTime", FileTime.fromMillis(2000));

    File[] files = dir.listFiles();
    MoreFiles.sortFilesByAccessTime(files);
    assertEquals(
        "Files short be sorted from most recently accessed to least recently accessed.",
        ImmutableList.of(fileW, fileZ, fileY, fileX),
        Arrays.asList(files));
  }

  @Test
  public void testMakeExecutable() throws IOException {
    File file = tmp.newFile();

    // If the file system does not support the executable permission, skip the test
    assumeTrue(file.setExecutable(false));
    assertFalse("File should not be executable", file.canExecute());
    MoreFiles.makeExecutable(file);
    assertTrue("File should be executable", file.canExecute());

    assumeTrue(file.setExecutable(true));
    assertTrue("File should be executable", file.canExecute());
    MoreFiles.makeExecutable(file);
    assertTrue("File should be executable", file.canExecute());
  }

  @Test
  public void testMakeExecutableOnPosix() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

    File file = tmp.newFile();
    Path path = file.toPath();

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Owner's execute permission should have been set",
        "r-x------",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---r-----"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Group's execute permission should have been set",
        "---r-x---",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("------r--"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Others' execute permission should have been set",
        "------r-x",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--r--r--"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "All execute permissions should have been set",
        "r-xr-xr-x",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xrw-rwx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Only group's execute permission should have been set",
        "r-xrwxrwx",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-w---x-wx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "-w---x-wx",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "---------",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));

    java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "rwxrwxrwx",
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)));
  }
}
