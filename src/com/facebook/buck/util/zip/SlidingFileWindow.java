/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Provides a ByteBuffer-like object for dealing with very large files. */
public final class SlidingFileWindow {
  @VisibleForTesting
  static SlidingFileWindow withByteBuffer(ByteBuffer buffer, int maxBufferSize) throws IOException {
    return new SlidingFileWindow(ByteBufferHolder.withByteBuffer(buffer, maxBufferSize), false, 0);
  }

  private final ByteBufferHolder holder;
  private final boolean isSlice;
  private final long sliceOffset;
  private long position;
  private long limit;

  public SlidingFileWindow(FileChannel fileChannel) throws IOException {
    this(ByteBufferHolder.withFileChannel(fileChannel), false, 0);
  }

  private SlidingFileWindow(ByteBufferHolder holder, long sliceOffset) throws IOException {
    this(holder, true, sliceOffset);
  }

  private SlidingFileWindow(ByteBufferHolder holder, boolean isSlice, long sliceOffset)
      throws IOException {
    this.holder = holder;
    this.isSlice = isSlice;
    this.sliceOffset = sliceOffset;
    position = sliceOffset;
    this.limit = holder.size;

    if (sliceOffset < 0) {
      throw new IOException("");
    }
  }

  /** Closes the SlidingFileWindow and the underlying ByteBuffer. */
  public void close() {
    // Only close the holder if this is not a slice; slices closes are ignored.
    if (!isSlice) {
      holder.releaseByteBufferMemory();
    }
  }

  /** See ByteBuffer#order. */
  public void order(ByteOrder byteOrder) {
    holder.order(byteOrder);
  }

  /** See ByteBuffer#position. */
  public long position() {
    return position - sliceOffset;
  }

  /** See ByteBuffer#position. */
  public SlidingFileWindow position(long newPosition) {
    position = newPosition + sliceOffset;
    return this;
  }

  /** See ByteBuffer#limit. */
  public long limit() {
    return limit - sliceOffset;
  }

  /** See ByteBuffer#limit. */
  public SlidingFileWindow limit(long newLimit) {
    limit = newLimit + sliceOffset;
    return this;
  }

  /** See ByteBuffer#slice. */
  public SlidingFileWindow duplicate() throws IOException {
    return new SlidingFileWindow(holder, sliceOffset).position(position()).limit(limit());
  }

  /** See ByteBuffer#slice. */
  public SlidingFileWindow slice() throws IOException {
    return slice(0);
  }

  /** Helper for slicing with a given offset. */
  public SlidingFileWindow slice(long offset) throws IOException {
    return new SlidingFileWindow(holder, position + offset);
  }

  /** Helper for slicing with a given offset and limit. */
  public SlidingFileWindow slice(long offset, long limit) throws IOException {
    return new SlidingFileWindow(holder, position + offset).limit(limit);
  }

  /**
   * Checks that the ByteBufferHolder has all of the requested data in memory, updates the current
   * position to reflect that the data about to be read has been read, and returns the index in the
   * ByteBuffer to read the data at.
   */
  private int checkWindowGetIndexAndAdvance(long size) throws IOException {
    holder.updateWindow(position, size);
    int result = holder.getRelativeIndex(position);
    position += size;
    return result;
  }

  /**
   * Checks that the ByteBufferHolder has all of the requested data in memory and returns the index
   * in the ByteBuffer to read the data at.
   */
  private int checkWindowAndGetIndex(long index, long size) throws IOException {
    holder.updateWindow(index + sliceOffset, size);
    return holder.getRelativeIndex(index + sliceOffset);
  }

  /** See ByteBuffer#get. */
  public byte get() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(1);
    return holder.byteBuffer.get(bufferIndex);
  }

  /** See ByteBuffer#get. */
  public SlidingFileWindow get(byte[] dst) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(dst.length);
    holder.byteBuffer.position(bufferIndex).get(dst);
    return this;
  }

  /** See ByteBuffer#getShort. */
  public short getShort() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(2);
    return holder.byteBuffer.getShort(bufferIndex);
  }

  /** See ByteBuffer#getShort. */
  public short getShort(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 2);
    return holder.byteBuffer.getShort(bufferIndex);
  }

  /** See ByteBuffer#getInt. */
  public int getInt(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 4);
    return holder.byteBuffer.getInt(bufferIndex);
  }

  /** See ByteBuffer#getLong. */
  public long getLong(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 8);
    return holder.byteBuffer.getLong(bufferIndex);
  }

  /** See ByteBuffer#putInt. */
  public SlidingFileWindow putInt(int index, int value) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 4);
    holder.byteBuffer.putInt(bufferIndex, value);
    return this;
  }

  /** See ByteBuffer#putInt. */
  public SlidingFileWindow putInt(int value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(4);
    holder.byteBuffer.putInt(bufferIndex, value);
    return this;
  }

  /** Inner class for managing the underlying ByteBuffer. */
  private abstract static class ByteBufferHolder {
    /** Builds a ByteBufferHolder based on a FileChannel. This is the normal path. */
    public static ByteBufferHolder withFileChannel(FileChannel fileChannel) throws IOException {
      return new ByteBufferHolder(fileChannel.size(), Integer.MAX_VALUE) {
        @Override
        protected ByteBuffer map(long start, int length) throws IOException {
          return fileChannel.map(FileChannel.MapMode.READ_WRITE, start, length);
        }
      };
    }

    /** Builds a ByteBufferHolder based on a pre-existing ByteBuffer, for tests. */
    public static ByteBufferHolder withByteBuffer(ByteBuffer buffer, int maxBufferSize)
        throws IOException {
      return new ByteBufferHolder(buffer.limit(), maxBufferSize) {
        @Override
        protected ByteBuffer map(long start, int length) throws IOException {
          return buffer.position((int) start).slice().limit(length);
        }
      };
    }

    private final long size;
    private final int maxBufferSize;

    private long windowStart = -1;
    private long windowEnd = -1;
    private ByteOrder byteOrder = null;
    private ByteBuffer byteBuffer = null;

    private ByteBufferHolder(long size, int maxBufferSize) throws IOException {
      this.size = size;
      this.maxBufferSize = maxBufferSize;

      // If the file is small enough to be mapped all at once, go ahead and do it.
      if (size <= maxBufferSize) {
        mapByteBuffer(0, (int) size);
      }
    }

    /** Implement this to actually build the new ByteBuffer as it moves. */
    protected abstract ByteBuffer map(long start, int length) throws IOException;

    /** Sets the order on the current ByteBuffer and retains it for all future ones. */
    public void order(ByteOrder byteOrder) {
      this.byteOrder = byteOrder;
      if (byteBuffer != null) {
        byteBuffer.order(byteOrder);
      }
    }

    /** Releases the ByteBuffer memory if it is allocated. */
    public void releaseByteBufferMemory() {
      if (byteBuffer != null) {
        ByteBufferUnmapper.unmap(byteBuffer);
        byteBuffer = null;
      }
    }

    /** Recreates the ByteBuffer , mapping it to a new window of the data. */
    private void mapByteBuffer(long start, int length) throws IOException {
      // Release the old data to prevent memory leaks;
      releaseByteBufferMemory();

      // Update the window and rebuild the buffer objects.
      windowStart = start;
      windowEnd = start + length;
      byteBuffer = map(start, length);

      // Restore the byte order.
      if (byteOrder != null) {
        byteBuffer.order(byteOrder);
      }
    }

    /** Checks that the current ByteBuffer window contains the requested data. */
    public void updateWindow(long start, long length) throws IOException {
      if (length > maxBufferSize) {
        throw new IllegalArgumentException(
            String.format(
                "SlidingFileWindow working data exceeds maximum buffer size (%s bytes)",
                maxBufferSize));
      }

      long end = start + length;

      // If the window already contains everything we need, then don't update it.
      if (start >= windowStart && end <= windowEnd) {
        return;
      }

      // If we're here, then the entire file can't be mapped into memory.
      // Create a new window, at maximum size, centered around the start point.
      long newStart = start;

      // Make sure the window is within the bounds of the underlying file.
      if (newStart < 0) {
        newStart = 0;
      } else if (newStart + maxBufferSize > size) {
        newStart = size - maxBufferSize;
      }

      mapByteBuffer(newStart, maxBufferSize);
    }

    /** Gets the relative index to use with the ByteBuffer for a given position. */
    public int getRelativeIndex(long index) {
      return (int) (index - windowStart);
    }
  }
}
