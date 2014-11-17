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

import com.facebook.buck.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import javax.annotation.Nullable;

/**
 * An implementation of an {@link OutputStream} for zip files that allows newer entries to overwrite
 * or refresh previously written entries.
 * <p>
 * This class works by spooling the bytes of each entry to a temporary holding file named after the
 * name of the {@link ZipEntry} being stored. Once the stream is closed, these files are spooled
 * off disk and written to the OutputStream given to the constructor.
 */
public class OverwritingZipOutputStream extends CustomZipOutputStream {
  // Attempt to maintain ordering of files that are added.
  private final Map<File, EntryAccounting> entries = Maps.newLinkedHashMap();
  private final File scratchDir;
  private final Clock clock;
  @Nullable private EntryAccounting currentEntry;
  /** Place-holder for bytes. */
  @Nullable private OutputStream currentOutput;

  public OverwritingZipOutputStream(Clock clock, OutputStream out) {
    super(out);
    this.clock = clock;

    try {
      scratchDir = Files.createTempDirectory("overwritingzip").toFile();
      // Reading the source, it seems like the temp dir isn't scheduled for deletion. We will delete
      // the directory when we close the stream, but if that method is never called, we'd leave
      // cruft on the FS. It's not foolproof, but try and avoid that.
      scratchDir.deleteOnExit();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void actuallyPutNextEntry(ZipEntry entry) throws IOException {
    // We calculate the actual offset when closing the stream, so 0 is fine.
    currentEntry = new EntryAccounting(clock, entry, /* currentOffset */ 0);

    long md5 = Hashing.md5().hashUnencodedChars(entry.getName()).asLong();
    String name = String.valueOf(md5);

    File file = new File(scratchDir, name);
    entries.put(file, currentEntry);
    if (file.exists() && !file.delete()) {
      throw new ZipException("Unable to delete existing file: " + entry.getName());
    }
    currentOutput = new BufferedOutputStream(new FileOutputStream(file));
  }

  @Override
  protected void actuallyCloseEntry() throws IOException {
    // We'll close the entry once we have the ultimate output stream and know the entry's location
    // within the generated zip.
    if (currentOutput != null) {
      currentOutput.close();
    }
    currentOutput = null;
    currentEntry = null;
  }

  @Override
  protected void actuallyWrite(byte[] b, int off, int len) throws IOException {
    Preconditions.checkNotNull(currentEntry);
    Preconditions.checkNotNull(currentOutput);
    currentEntry.write(currentOutput, b, off, len);
  }

  @Override
  protected void actuallyClose() throws IOException {
    long currentOffset = 0;

    for (Map.Entry<File, EntryAccounting> mapEntry : entries.entrySet()) {
      EntryAccounting entry = mapEntry.getValue();
      entry.setOffset(currentOffset);
      currentOffset += entry.writeLocalFileHeader(delegate);

      Files.copy(mapEntry.getKey().toPath(), delegate);

      // If `entry.close()` returns 0, this means that we're using the STORED method, which doesn't
      // perform compression.  In this case, we're responsible for manually updating the offset
      // accounted for by the written output.  However, if a non-0 size is returned, we're using
      // the DEFLATED method, so we don't update the offset, as entry.close will write the file
      // header, which contains the correct size of the output.
      long closeSize = entry.close(delegate);
      if (closeSize == 0) {
        currentOffset += entry.getSize();
      } else {
        currentOffset += closeSize;
      }
    }

    new CentralDirectory().writeCentralDirectory(delegate, currentOffset, entries.values());

    delegate.close();

    // Ideally we'd just do this, but that introduces some nasty circular references. *sigh* Instead
    // we'll do this the tedious way by hand.
    // MoreFiles.deleteRecursively(scratchDir.toPath());

    SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc == null) {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
        throw exc;
      }
    };
    Files.walkFileTree(scratchDir.toPath(), visitor);
  }
}
