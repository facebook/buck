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

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.FileLike;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Helper to write a Zip file used by {@link DefaultZipSplitter}.
 */
class DefaultZipOutputStreamHelper implements ZipOutputStreamHelper {

  private final ZipOutputStream outStream;
  private final Set<String> entryNames = Sets.newHashSet();
  private final long zipSizeHardLimit;
  private final File reportFile;

  private long currentSize;

  DefaultZipOutputStreamHelper(File outputFile, long zipSizeHardLimit, File reportDir)
      throws FileNotFoundException {
    this.outStream = new ZipOutputStream(
        new BufferedOutputStream(
            new FileOutputStream(outputFile)));
    this.zipSizeHardLimit = zipSizeHardLimit;
    this.reportFile = new File(reportDir, outputFile.getName() + ".txt");
  }

  public long getCurrentSize() {
    return currentSize;
  }

  private boolean isEntryTooBig(long entrySize) {
    return (currentSize + entrySize > zipSizeHardLimit);
  }

  private long getSize(FileLike fileLike) {
    return fileLike.getSize();
  }

  @Override
  public boolean canPutEntry(FileLike fileLike) {
    return !isEntryTooBig(getSize(fileLike));
  }

  @Override
  public boolean containsEntry(FileLike fileLike) {
    return entryNames.contains(fileLike.getRelativePath());
  }

  @Override
  public void putEntry(FileLike fileLike) throws IOException {
    String name = fileLike.getRelativePath();
    // Tracks unique entry names and avoids duplicates.  This is, believe it or not, how
    // proguard seems to handle merging multiple -injars into a single -outjar.
    if (!containsEntry(fileLike)) {
      entryNames.add(name);
      outStream.putNextEntry(new ZipEntry(name));
      try (InputStream in = fileLike.getInput()) {
        ByteStreams.copy(in, outStream);
      }

      // Make sure FileLike#getSize didn't lie (or we forgot to call canPutEntry).
      long entrySize = getSize(fileLike);
      Preconditions.checkState(!isEntryTooBig(entrySize),
          "Putting entry %s (%s) exceeded maximum size of %s", name, entrySize, zipSizeHardLimit);
      currentSize += entrySize;

      String report = String.format("%s %s\n", entrySize, name);
      Files.append(report, reportFile, Charsets.UTF_8);
    }
  }

  @Override
  public void close() throws IOException {
    outStream.close();
  }
}
