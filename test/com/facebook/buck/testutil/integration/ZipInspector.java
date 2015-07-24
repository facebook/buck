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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;

import org.hamcrest.Matchers;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipInspector {

  private final File zipFile;
  private final ImmutableSet<String> zipFileEntries;

  public ZipInspector(File zip) throws IOException {
    this.zipFile = Preconditions.checkNotNull(zip);

    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    try (ZipFile zipFile = new ZipFile(zip)) {
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

  public void assertFilesDoNotExist(String...pathsRelativeToRoot) {
    for (String path : pathsRelativeToRoot) {
      assertFileDoesNotExist(path);
    }
  }

  public void assertFileContents(String pathRelativeToRoot, String expected) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile)) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      assertThat(
          CharStreams.toString(new InputStreamReader(zipFile.getInputStream(entry))),
          Matchers.equalTo(expected));
    }
  }

  public ImmutableSet<String> getZipFileEntries() {
    return zipFileEntries;
  }

  public long getCrc(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile)) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      long crc = entry.getCrc();
      Preconditions.checkState(crc != -1,
          "Error accessing crc for entry: %s",
          pathRelativeToRoot);
      return crc;
    }
  }

  public long getSize(String pathRelativeToRoot) throws IOException {
    try (ZipFile zipFile = new ZipFile(this.zipFile)) {
      ZipEntry entry = zipFile.getEntry(pathRelativeToRoot);
      long size = entry.getSize();
      Preconditions.checkState(size != -1,
          "Error accessing size for entry: %s",
          pathRelativeToRoot);
      return size;
    }
  }
}
