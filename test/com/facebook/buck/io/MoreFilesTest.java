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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

public class MoreFilesTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testDeleteRecursively() throws IOException {
    tmp.newFile(".dotfile");
    tmp.newFile("somefile");
    tmp.newFolder("foo");
    tmp.newFile("foo/bar");
    tmp.newFolder("foo/baz");
    tmp.newFile("foo/baz/biz");
    assertEquals("There should be files to delete.", 3, tmp.getRoot().toFile().listFiles().length);
    MoreFiles.deleteRecursively(tmp.getRoot());
    assertNull(tmp.getRoot().toFile().listFiles());
  }

  @Test
  public void testWriteLinesToFile() throws IOException {
    Path outputFile = tmp.newFile("output.txt");
    ImmutableList<String> lines = ImmutableList.of(
        "The",
        "quick brown fox",
        "jumps over",
        "the lazy dog.");
    MoreFiles.writeLinesToFile(lines, outputFile);

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
    MoreFiles.sortFilesByAccessTime(files);
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
    MoreFiles.makeExecutable(file);
    assertTrue("File should be executable", file.toFile().canExecute());

    assumeTrue(file.toFile().setExecutable(true));
    assertTrue("File should be executable", Files.isExecutable(file));
    MoreFiles.makeExecutable(file);
    assertTrue("File should be executable", Files.isExecutable(file));
  }

  @Test
  public void testMakeExecutableOnPosix() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

    Path file = tmp.newFile();

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Owner's execute permission should have been set",
        "r-x------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---r-----"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Group's execute permission should have been set",
        "---r-x---",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("------r--"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Others' execute permission should have been set",
        "------r-x",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "All execute permissions should have been set",
        "r-xr-xr-x",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xrw-rwx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "Only group's execute permission should have been set",
        "r-xrwxrwx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w---x-wx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "-w---x-wx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "---------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxrwxrwx"));
    MoreFiles.makeExecutable(file);
    assertEquals(
        "No permissions should have been changed",
        "rwxrwxrwx",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }
}
