/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem.skylark;

import static org.junit.Assert.*;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Scanner;
import org.junit.Before;
import org.junit.Test;

public class SkylarkFilesystemTest {

  private ProjectFilesystem projectFilesystem;
  private SkylarkFilesystem skylarkFilesystem;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
  }

  @Test
  public void supportsModifications() throws Exception {
    assertTrue(skylarkFilesystem.supportsModifications());
  }

  @Test
  public void supportsSymbolicLinksNatively() throws Exception {
    assertTrue(skylarkFilesystem.supportsSymbolicLinksNatively());
  }

  @Test
  public void supportsHardLinksNatively() throws Exception {
    assertTrue(skylarkFilesystem.supportsHardLinksNatively());
  }

  @Test
  public void isFilePathCaseSensitive() throws Exception {
    assertTrue(skylarkFilesystem.isFilePathCaseSensitive());
  }

  @Test
  public void createDirectory() throws Exception {
    java.nio.file.Path directory = projectFilesystem.resolve("/dir");
    assertFalse(projectFilesystem.isDirectory(directory));
    assertTrue(skylarkFilesystem.createDirectory(toSkylarkPath(directory)));
    assertTrue(projectFilesystem.isDirectory(directory));
  }

  @Test
  public void createExistingDirectory() throws Exception {
    java.nio.file.Path directory = projectFilesystem.resolve("/dir");
    projectFilesystem.mkdirs(directory);
    assertFalse(skylarkFilesystem.createDirectory(toSkylarkPath(directory)));
  }

  @Test
  public void getFileSize() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    projectFilesystem.writeContentsToPath("foo", filePath);
    assertEquals(
        3, skylarkFilesystem.getFileSize(toSkylarkPath(filePath), /* followSymlinks */ false));
  }

  private Path toSkylarkPath(String path) {
    return skylarkFilesystem.getPath(path);
  }

  private Path toSkylarkPath(java.nio.file.Path path) {
    return toSkylarkPath(path.toString());
  }

  @Test
  public void delete() throws Exception {
    java.nio.file.Path directory = projectFilesystem.resolve("/dir");
    projectFilesystem.mkdirs(directory);
    assertTrue(projectFilesystem.isDirectory(directory));
    skylarkFilesystem.delete(toSkylarkPath(directory));
    assertFalse(projectFilesystem.isDirectory(directory));
  }

  @Test
  public void getLastModifiedTime() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    projectFilesystem.writeContentsToPath("foo", filePath);
    FileTime expectedLastModifiedTime = projectFilesystem.getLastModifiedTime(filePath);
    assertEquals(
        expectedLastModifiedTime.toMillis(),
        skylarkFilesystem.getLastModifiedTime(toSkylarkPath(filePath), /* followSymlinks */ false));
  }

  @Test
  public void setLastModifiedTime() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    projectFilesystem.writeContentsToPath("foo", filePath);
    skylarkFilesystem.setLastModifiedTime(toSkylarkPath(filePath), 42L);
    FileTime expectedLastModifiedTime = projectFilesystem.getLastModifiedTime(filePath);
    assertEquals(expectedLastModifiedTime.toMillis(), 42L);
  }

  @Test
  public void createSymbolicLink() throws Exception {
    java.nio.file.Path symlinkPath = projectFilesystem.resolve("/symlink");
    skylarkFilesystem.createSymbolicLink(
        toSkylarkPath(symlinkPath), PathFragment.create("/actual"));
    assertEquals("/actual", projectFilesystem.readSymLink(symlinkPath).toString());
  }

  @Test
  public void readSymbolicLink() throws Exception {
    java.nio.file.Path symLink = projectFilesystem.resolve("/symlink");
    projectFilesystem.createSymLink(
        symLink, projectFilesystem.resolve("/real_file"), /* force */ true);
    assertEquals(
        PathFragment.create("/real_file"),
        skylarkFilesystem.readSymbolicLink(toSkylarkPath(symLink)));
  }

  @Test
  public void exists() throws Exception {
    assertFalse(
        skylarkFilesystem.exists(toSkylarkPath("/non_existing_path"), /* followSymlinks */ false));
    java.nio.file.Path existingPath = projectFilesystem.resolve("/existing_path");
    projectFilesystem.createNewFile(existingPath);
    assertTrue(skylarkFilesystem.exists(toSkylarkPath(existingPath), /* followSymlinks */ false));
  }

  @Test
  public void getDirectoryEntries() throws Exception {
    java.nio.file.Path directory = projectFilesystem.resolve("/dir");
    projectFilesystem.mkdirs(directory);
    projectFilesystem.createNewFile(directory.resolve("file1"));
    projectFilesystem.createNewFile(directory.resolve("file2"));
    assertEquals(
        ImmutableList.of(toSkylarkPath("/dir/file1"), toSkylarkPath("/dir/file2")),
        skylarkFilesystem.getDirectoryEntries(toSkylarkPath("/dir")));
  }

  @Test
  public void isReadable() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    assertFalse(skylarkFilesystem.isReadable(toSkylarkPath(filePath)));
    projectFilesystem.createNewFile(filePath);
    assertTrue(skylarkFilesystem.isReadable(toSkylarkPath(filePath)));
  }

  @Test
  public void setReadable() throws Exception {
    // need to create a real filesystem because in-memory FS does not support file operations
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    java.nio.file.Path filePath = projectFilesystem.resolve("file");
    Files.createFile(filePath);
    assertTrue(filePath.toFile().setReadable(false));
    assertFalse(Files.isReadable(filePath));
    skylarkFilesystem.setReadable(
        toSkylarkPath(filePath.toAbsolutePath()), /* followSymlinks */ false);
    assertTrue(Files.isReadable(filePath));
  }

  @Test
  public void isWritable() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    assertFalse(skylarkFilesystem.isWritable(toSkylarkPath(filePath)));
    projectFilesystem.createNewFile(filePath);
    assertTrue(skylarkFilesystem.isWritable(toSkylarkPath(filePath)));
  }

  @Test
  public void setWritable() throws Exception {
    // need to create a real filesystem because in-memory FS does not support file operations
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    java.nio.file.Path filePath = projectFilesystem.resolve("file");
    Files.createFile(filePath);
    assertTrue(filePath.toFile().setWritable(false));
    assertFalse(Files.isWritable(filePath));
    skylarkFilesystem.setWritable(
        toSkylarkPath(filePath.toAbsolutePath()), /* followSymlinks */ false);
    assertTrue(Files.isWritable(filePath));
  }

  @Test
  public void isExecutable() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    assertFalse(skylarkFilesystem.isExecutable(toSkylarkPath(filePath)));
    projectFilesystem.createNewFile(filePath);
    assertTrue(skylarkFilesystem.isExecutable(toSkylarkPath(filePath)));
  }

  @Test
  public void setExecutable() throws Exception {
    // need to create a real filesystem because in-memory FS does not support file operations
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    java.nio.file.Path filePath = projectFilesystem.resolve("file");
    Files.createFile(filePath);
    assertTrue(filePath.toFile().setExecutable(false));
    assertFalse(Files.isExecutable(filePath));
    skylarkFilesystem.setExecutable(
        toSkylarkPath(filePath.toAbsolutePath()), /* followSymlinks */ false);
    assertTrue(Files.isExecutable(filePath));
  }

  @Test
  public void getInputStream() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    String content = "foo";
    projectFilesystem.writeContentsToPath(content, filePath);
    try (Scanner scanner = new Scanner(skylarkFilesystem.getInputStream(toSkylarkPath(filePath)))) {
      assertEquals(content, scanner.next());
    }
  }

  @Test
  public void getOutputStream() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    String content = "foo";
    projectFilesystem.createNewFile(filePath);
    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(
            skylarkFilesystem.getOutputStream(
                toSkylarkPath(filePath), /* followSymlinks */ false))) {
      writer.write(content);
    }
    assertEquals(content, projectFilesystem.readFirstLine(filePath).get());
  }

  @Test
  public void renameTo() throws Exception {
    java.nio.file.Path sourcePath = projectFilesystem.resolve("/source");
    java.nio.file.Path destinationPath = projectFilesystem.resolve("/destination");
    projectFilesystem.createNewFile(sourcePath);
    skylarkFilesystem.renameTo(toSkylarkPath(sourcePath), toSkylarkPath(destinationPath));
    assertTrue(projectFilesystem.exists(destinationPath));
    assertFalse(projectFilesystem.exists(sourcePath));
  }

  @Test
  public void createFSDependentHardLink() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    java.nio.file.Path hardLinkPath = projectFilesystem.resolve("/hard_link");
    projectFilesystem.createNewFile(filePath);
    skylarkFilesystem.createFSDependentHardLink(
        toSkylarkPath(hardLinkPath), toSkylarkPath(filePath));
    assertTrue(projectFilesystem.exists(hardLinkPath));
  }

  @Test
  public void statForRegularFile() throws Exception {
    java.nio.file.Path filePath = projectFilesystem.resolve("/file");
    projectFilesystem.writeContentsToPath("foo", filePath);
    FileStatus fileStatus =
        skylarkFilesystem.stat(toSkylarkPath(filePath), /* followSymlinks */ false);
    assertFalse(fileStatus.isSpecialFile());
    assertFalse(fileStatus.isDirectory());
    assertTrue(fileStatus.isFile());
    assertFalse(fileStatus.isSymbolicLink());
    assertEquals(3L, fileStatus.getSize());
    assertEquals(Files.getLastModifiedTime(filePath).toMillis(), fileStatus.getLastModifiedTime());
    assertEquals(Files.getLastModifiedTime(filePath).toMillis(), fileStatus.getLastChangeTime());
    assertEquals(-1, fileStatus.getNodeId());
  }

  @Test
  public void statForDirectory() throws Exception {
    java.nio.file.Path directory = projectFilesystem.resolve("/dir");
    projectFilesystem.mkdirs(directory);
    FileStatus fileStatus =
        skylarkFilesystem.stat(toSkylarkPath(directory), /* followSymlinks */ false);
    assertFalse(fileStatus.isSpecialFile());
    assertTrue(fileStatus.isDirectory());
    assertFalse(fileStatus.isFile());
    assertFalse(fileStatus.isSymbolicLink());
    assertEquals(0L, fileStatus.getSize());
    assertEquals(Files.getLastModifiedTime(directory).toMillis(), fileStatus.getLastModifiedTime());
    assertEquals(Files.getLastModifiedTime(directory).toMillis(), fileStatus.getLastChangeTime());
    assertEquals(-1, fileStatus.getNodeId());
  }

  @Test
  public void statForSymlink() throws Exception {
    java.nio.file.Path realFile = projectFilesystem.resolve("/real_path");
    java.nio.file.Path symlinkPath = projectFilesystem.resolve("/symlink");
    projectFilesystem.createSymLink(symlinkPath, realFile, /* force */ true);
    FileStatus fileStatus =
        skylarkFilesystem.stat(toSkylarkPath(symlinkPath), /* followSymlinks */ false);
    assertFalse(fileStatus.isSpecialFile());
    assertFalse(fileStatus.isDirectory());
    assertFalse(fileStatus.isFile());
    assertTrue(fileStatus.isSymbolicLink());
    assertEquals(0L, fileStatus.getSize());
    assertEquals(-1, fileStatus.getNodeId());
  }
}
