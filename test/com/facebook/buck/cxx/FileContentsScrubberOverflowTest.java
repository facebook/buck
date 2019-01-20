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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.file.FileContentsScrubber;
import com.facebook.buck.io.file.FileScrubber;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class FileContentsScrubberOverflowTest {

  private static class FakeFileChannel extends FileChannel {

    private final AtomicBoolean unread = new AtomicBoolean(true);

    // Ten digits to match size metadata.  Doesn't fit in 32-bits.
    public static final long FILE_SIZE = 6000000000L;

    // From ObjectFileScrubbers.
    public static final int ENTRY_SIZE = 16 + 12 + 6 + 6 + 8 + 10 + 2;

    // Pretend to read out an object file's metadata.
    @Override
    public int read(ByteBuffer buf) {

      // Scrubber is trying to read the global header, do nothing.
      if (unread.getAndSet(false)) {
        buf.put(ObjectFileScrubbers.GLOBAL_HEADER);
        return buf.capacity();
      }

      // None of these values matter except for size and magic.
      byte[] filename =
          new byte[] {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
          };

      byte[] mtime = Arrays.copyOf(filename, 12);
      byte[] owner = Arrays.copyOf(filename, 6);
      byte[] group = Arrays.copyOf(filename, 6);
      byte[] mode = Arrays.copyOf(filename, 8);

      // Half so scrubber thinks there are two entries.  That way it'll have to do math to update
      // its position (and we want to see if it overflows).
      byte[] size =
          new byte[] {
            '3', '0', '0', '0', '0',
            '0', '0', '0', '0', '0',
          };

      byte[] magic = ObjectFileScrubbers.END_OF_FILE_HEADER_MARKER;

      buf.put(filename);
      buf.put(mtime);
      buf.put(owner);
      buf.put(group);
      buf.put(mode);
      buf.put(size);
      buf.put(magic);

      return ENTRY_SIZE;
    }

    @Override
    public int write(ByteBuffer buf) {
      return ENTRY_SIZE;
    }

    @Override
    public FileChannel position(long newPosition) {
      return this;
    }

    @Override
    public long size() {
      return FILE_SIZE;
    }

    /* Unused. */

    @Override
    public void force(boolean metaData) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void implCloseChannel() {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MappedByteBuffer map(FileChannel.MapMode mode, long position, long size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int read(ByteBuffer dst, long position) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long position() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src, long position) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileChannel truncate(long size) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void thatFileSizesOver32BitsIsOkay() throws IOException, FileScrubber.ScrubException {
    FileContentsScrubber scrubber =
        ObjectFileScrubbers.createDateUidGidScrubber(ObjectFileScrubbers.PaddingStyle.LEFT);
    scrubber.scrubFile(new FakeFileChannel());
  }
}
