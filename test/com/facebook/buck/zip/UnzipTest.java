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

package com.facebook.buck.zip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.MorePosixFilePermissions;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class UnzipTest {
  private static final byte[] DUMMY_FILE_CONTENTS = "BUCK Unzip Test String!\nNihao\n".getBytes();

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private Path zipFile;

  @Before
  public void setUp() {
    zipFile = tmpFolder.getRoot().resolve("tmp.zip");
  }

  @Test
  public void testExtractZipFile() throws InterruptedException, IOException {

    try (Zip zip = new Zip(zipFile, true)) {
      zip.add("1.bin", DUMMY_FILE_CONTENTS);
      zip.add("subdir/2.bin", DUMMY_FILE_CONTENTS);
      zip.addDir("emptydir");
    }

    Path extractFolder = tmpFolder.newFolder();
    ImmutableList<Path> result =
        Unzip.extractZipFile(
            zipFile.toAbsolutePath(),
            extractFolder.toAbsolutePath(),
            Unzip.ExistingFileMode.OVERWRITE);
    assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve("1.bin")));
    Path bin2 = extractFolder.toAbsolutePath().resolve("subdir/2.bin");
    assertTrue(Files.exists(bin2));
    assertTrue(Files.isDirectory(extractFolder.toAbsolutePath().resolve("emptydir")));
    try (InputStream input = Files.newInputStream(bin2)) {
      byte[] buffer = new byte[DUMMY_FILE_CONTENTS.length];
      int bytesRead = input.read(buffer, 0, DUMMY_FILE_CONTENTS.length);
      assertEquals(DUMMY_FILE_CONTENTS.length, bytesRead);
      for (int i = 0; i < DUMMY_FILE_CONTENTS.length; i++) {
        assertEquals(DUMMY_FILE_CONTENTS[i], buffer[i]);
      }
    }
    assertEquals(
        ImmutableList.of(extractFolder.resolve("1.bin"), extractFolder.resolve("subdir/2.bin")),
        result);
  }

  @Test
  public void testExtractZipFilePreservesExecutePermissionsAndModificationTime()
      throws InterruptedException, IOException {

    // getFakeTime returs time with some non-zero millis. By doing division and multiplication by
    // 1000 we get rid of that.
    final long time = ZipConstants.getFakeTime() / 1000 * 1000;

    // Create a simple zip archive using apache's commons-compress to store executable info.
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile.toFile())) {
      ZipArchiveEntry entry = new ZipArchiveEntry("test.exe");
      entry.setUnixMode(
          (int) MorePosixFilePermissions.toMode(PosixFilePermissions.fromString("r-x------")));
      entry.setSize(DUMMY_FILE_CONTENTS.length);
      entry.setMethod(ZipEntry.STORED);
      entry.setTime(time);
      zip.putArchiveEntry(entry);
      zip.write(DUMMY_FILE_CONTENTS);
      zip.closeArchiveEntry();
    }

    // Now run `Unzip.extractZipFile` on our test zip and verify that the file is executable.
    Path extractFolder = tmpFolder.newFolder();
    ImmutableList<Path> result =
        Unzip.extractZipFile(
            zipFile.toAbsolutePath(),
            extractFolder.toAbsolutePath(),
            Unzip.ExistingFileMode.OVERWRITE);
    Path exe = extractFolder.toAbsolutePath().resolve("test.exe");
    assertTrue(Files.exists(exe));
    assertThat(Files.getLastModifiedTime(exe).toMillis(), Matchers.equalTo(time));
    assertTrue(Files.isExecutable(exe));
    assertEquals(ImmutableList.of(extractFolder.resolve("test.exe")), result);
  }

  @Test
  public void testExtractSymlink() throws InterruptedException, IOException {
    assumeThat(Platform.detect(), Matchers.is(Matchers.not(Platform.WINDOWS)));

    // Create a simple zip archive using apache's commons-compress to store executable info.
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile.toFile())) {
      ZipArchiveEntry entry = new ZipArchiveEntry("link.txt");
      entry.setUnixMode((int) MoreFiles.S_IFLNK);
      String target = "target.txt";
      entry.setSize(target.getBytes(Charsets.UTF_8).length);
      entry.setMethod(ZipEntry.STORED);
      zip.putArchiveEntry(entry);
      zip.write(target.getBytes(Charsets.UTF_8));
      zip.closeArchiveEntry();
    }

    // Now run `Unzip.extractZipFile` on our test zip and verify that the file is executable.
    Path extractFolder = tmpFolder.newFolder();
    Unzip.extractZipFile(
        zipFile.toAbsolutePath(), extractFolder.toAbsolutePath(), Unzip.ExistingFileMode.OVERWRITE);
    Path link = extractFolder.toAbsolutePath().resolve("link.txt");
    assertTrue(Files.isSymbolicLink(link));
    assertThat(Files.readSymbolicLink(link).toString(), Matchers.equalTo("target.txt"));
  }

  @Test
  public void testExtractWeirdIndex() throws InterruptedException, IOException {

    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile.toFile())) {
      zip.putArchiveEntry(new ZipArchiveEntry("foo/bar/baz"));
      zip.write(DUMMY_FILE_CONTENTS, 0, DUMMY_FILE_CONTENTS.length);
      zip.closeArchiveEntry();
      zip.putArchiveEntry(new ZipArchiveEntry("foo/"));
      zip.closeArchiveEntry();
      zip.putArchiveEntry(new ZipArchiveEntry("qux/"));
      zip.closeArchiveEntry();
    }

    Path extractFolder = tmpFolder.newFolder();
    ImmutableList<Path> result =
        Unzip.extractZipFile(
            zipFile.toAbsolutePath(),
            extractFolder.toAbsolutePath(),
            Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve("foo")));
    assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve("foo/bar/baz")));

    assertEquals(ImmutableList.of(extractFolder.resolve("foo/bar/baz")), result);
  }

  @Test
  public void testNonCanonicalPaths() throws InterruptedException, IOException {
    String names[] = {
      "foo/./", "foo/./bar/", "foo/./bar/baz.cpp", "foo/./bar/baz.h",
    };

    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile.toFile())) {
      for (String name : names) {
        zip.putArchiveEntry(new ZipArchiveEntry(name));
        zip.closeArchiveEntry();
      }
    }

    Path extractFolder = tmpFolder.newFolder();
    Unzip.extractZipFile(
        zipFile.toAbsolutePath(),
        extractFolder.toAbsolutePath(),
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    for (String name : names) {
      assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve(name)));
    }
    Unzip.extractZipFile(
        zipFile.toAbsolutePath(),
        extractFolder.toAbsolutePath(),
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    for (String name : names) {
      assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve(name)));
    }
  }

  @Test
  public void testParentDirPaths() throws InterruptedException, IOException {

    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile.toFile())) {
      // It seems very unlikely that a zip file would contain ".." paths, but handle it anyways.
      zip.putArchiveEntry(new ZipArchiveEntry("foo/bar/"));
      zip.closeArchiveEntry();
      zip.putArchiveEntry(new ZipArchiveEntry("foo/bar/../"));
      zip.closeArchiveEntry();
    }

    Path extractFolder = tmpFolder.newFolder();
    Unzip.extractZipFile(
        zipFile.toAbsolutePath(),
        extractFolder.toAbsolutePath(),
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve("foo")));
    assertTrue(Files.exists(extractFolder.toAbsolutePath().resolve("foo/bar")));
  }
}
