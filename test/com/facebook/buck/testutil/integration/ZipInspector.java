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

package com.facebook.buck.testutil.integration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.MoreStringsForTests;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.hamcrest.Matchers;

public class ZipInspector {

  private final Path zipFile;
  private final ImmutableSet<String> zipFileEntries;

  public ZipInspector(Path zip) throws IOException {
    this.zipFile = Preconditions.checkNotNull(zip);

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        builder.add(entries.nextElement().getName());
      }
    }
    this.zipFileEntries = builder.build();
  }

  public void assertFileExists(String pathRelativeToRoot) {
    assertThat(zipFileEntries, hasItem(pathRelativeToRoot));
  }

  public void assertFileDoesNotExist(String pathRelativeToRoot) {
    assertThat(zipFileEntries, not(hasItem((pathRelativeToRoot))));
  }

  public void assertFilesDoNotExist(String... pathsRelativeToRoot) {
    for (String path : pathsRelativeToRoot) {
      assertFileDoesNotExist(path);
    }
  }

  public void assertFileContents(String pathRelativeToRoot, String expected) throws IOException {
    assertThat(
        new String(getFileContents(pathRelativeToRoot), Charsets.UTF_8),
        MoreStringsForTests.equalToIgnoringPlatformNewlines(expected));
  }

  public void assertFileContents(Path pathRelativeToRoot, String expected) throws IOException {
    assertFileContents(MorePaths.pathWithUnixSeparators(pathRelativeToRoot), expected);
  }

  public void assertFileContains(String pathRelativeToRoot, String expected) throws IOException {
    assertThat(
        new String(getFileContents(pathRelativeToRoot), Charsets.UTF_8),
        MoreStringsForTests.containsIgnoringPlatformNewlines(expected));
  }

  public void assertFileContains(Path pathRelativeToRoot, String expected) throws IOException {
    assertFileContains(MorePaths.pathWithUnixSeparators(pathRelativeToRoot), expected);
  }

  public byte[] getFileContents(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile.toFile())) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      return ByteStreams.toByteArray(zipFile.getInputStream(entry));
    }
  }

  public ImmutableSet<String> getZipFileEntries() {
    return zipFileEntries;
  }

  public long getCrc(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile.toFile())) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      long crc = entry.getCrc();
      Preconditions.checkState(crc != -1, "Error accessing crc for entry: %s", pathRelativeToRoot);
      return crc;
    }
  }

  public long getSize(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile.toFile())) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      long size = entry.getSize();
      Preconditions.checkState(
          size != -1, "Error accessing size for entry: %s", pathRelativeToRoot);
      return size;
    }
  }

  public void assertFileIsCompressed(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile.toFile())) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      assertThat(entry.getMethod(), is(not(ZipEntry.STORED)));
      assertThat(entry.getCompressedSize(), Matchers.lessThan(entry.getSize()));
    }
  }

  public void assertFileIsNotCompressed(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile.toFile())) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      assertThat(entry.getMethod(), is(ZipEntry.STORED));
      assertThat(entry.getCompressedSize(), Matchers.equalTo(entry.getSize()));
    }
  }
}
