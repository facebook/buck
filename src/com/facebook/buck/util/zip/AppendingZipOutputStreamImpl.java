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

package com.facebook.buck.util.zip;

import com.facebook.buck.util.timing.Clock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import javax.annotation.Nullable;

/**
 * A drop-in replacement for (@link java.util.zip.ZipOutStream} that supports the ability to set a
 * compression level and allows multiple entries with the same name.
 *
 * <p><a href="https://users.cs.jmu.edu/buchhofp/forensics/formats/pkzip.html">
 * https://users.cs.jmu.edu/buchhofp/forensics/formats/pkzip.html </a> <a
 * href="http://www.pkware.com/documents/casestudies/APPNOTE.TXT">
 * http://www.pkware.com/documents/casestudies/APPNOTE.TXT </a>
 */
class AppendingZipOutputStreamImpl implements CustomZipOutputStream.Impl {

  private final OutputStream delegate;
  private final boolean throwExceptionsOnDuplicate;
  private final Clock clock;
  private long currentOffset = 0;
  private List<EntryAccounting> entries = new LinkedList<>();
  @Nullable private EntryAccounting currentEntry = null;

  private Set<String> seenNames = new HashSet<>();

  public AppendingZipOutputStreamImpl(
      Clock clock, OutputStream stream, boolean throwExceptionsOnDuplicate) {
    this.delegate = stream;
    this.clock = clock;
    this.throwExceptionsOnDuplicate = throwExceptionsOnDuplicate;
  }

  @Override
  public void actuallyWrite(byte[] b, int off, int len) throws IOException {
    Objects.requireNonNull(currentEntry);
    currentEntry.write(delegate, b, off, len);
  }

  @Override
  public void actuallyPutNextEntry(ZipEntry entry) throws IOException {
    if (throwExceptionsOnDuplicate && !seenNames.add(entry.getName())) {
      // Same exception as ZipOutputStream.
      throw new ZipException("duplicate entry: " + entry.getName());
    }

    currentEntry = new EntryAccounting(clock, entry, currentOffset);
    entries.add(currentEntry);

    currentOffset += currentEntry.writeLocalFileHeader(delegate);
  }

  @Override
  public void actuallyCloseEntry() throws IOException {
    if (currentEntry == null) {
      return; // no-op
    }

    currentOffset += currentEntry.finish(delegate);

    currentEntry = null;
  }

  @Override
  public void actuallyClose() throws IOException {
    new CentralDirectory().writeCentralDirectory(delegate, currentOffset, entries);

    delegate.close();
  }
}
