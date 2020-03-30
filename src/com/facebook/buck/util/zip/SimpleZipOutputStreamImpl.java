/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.zip;

import com.facebook.buck.util.timing.Clock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import javax.annotation.Nullable;

/**
 * An implementation of {@link CustomZipOutputStream.Impl} that doesn't perform any checks around
 * duplicate entries.
 */
class SimpleZipOutputStreamImpl implements CustomZipOutputStream.Impl {

  private final OutputStream delegate;
  private final Clock clock;
  private long currentOffset = 0;
  private List<EntryAccounting> entries = new LinkedList<>();
  @Nullable private EntryAccounting currentEntry = null;

  public SimpleZipOutputStreamImpl(Clock clock, OutputStream stream) {
    this.delegate = stream;
    this.clock = clock;
  }

  @Override
  public void actuallyWrite(byte[] b, int off, int len) throws IOException {
    Objects.requireNonNull(currentEntry).write(delegate, b, off, len);
  }

  @Override
  public void actuallyPutNextEntry(ZipEntry entry) throws IOException {
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
