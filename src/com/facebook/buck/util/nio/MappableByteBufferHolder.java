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

package com.facebook.buck.util.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Helper class that actually maintains the underlying ByteBuffer for LargeByteBuffer. */
abstract class MappableByteBufferHolder {
  private final long size;
  private final int maxBufferSize;

  private long windowStart = -1;
  private long windowEnd = -1;
  private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
  private ByteBuffer byteBuffer = null;

  MappableByteBufferHolder(long size, int maxBufferSize) throws IOException {
    this.size = size;
    this.maxBufferSize = maxBufferSize;

    // If the file is small enough to be mapped all at once, go ahead and do it.
    if (size <= maxBufferSize) {
      mapByteBuffer(0, (int) size);
    }
  }

  /** Implement this to actually build the new ByteBuffer as it moves. */
  protected abstract ByteBuffer map(long start, int length) throws IOException;

  public long getSize() {
    return size;
  }

  /** Sets the order on the current ByteBuffer and retains it for all future ones. */
  public void order(ByteOrder byteOrder) {
    this.byteOrder = byteOrder;
    if (byteBuffer != null) {
      byteBuffer.order(byteOrder);
    }
  }

  /** Gets the order used for all underlying ByteBuffers. */
  public ByteOrder order() {
    return this.byteOrder;
  }

  /** Releases the ByteBuffer memory if it is allocated. */
  public void releaseByteBufferMemory() {
    if (byteBuffer != null) {
      ByteBufferUnmapper.unmap(byteBuffer);
      byteBuffer = null;
    }
  }

  /** Recreates the ByteBuffer, mapping it to a new window of the data. */
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
              "SlidingFileLargeByteBuffer working data exceeds maximum buffer size (%s bytes)",
              maxBufferSize));
    }

    long end = start + length;

    // If the window already contains everything we need, then don't update it.
    if (start >= windowStart && end <= windowEnd) {
      return;
    }

    if (start < 0 || end > size) {
      throw new IndexOutOfBoundsException(
          String.format(
              "Tried to create window from %s to %s (only %s bytes available)", start, end, size));
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

  public ByteBuffer getByteBuffer() {
    return byteBuffer;
  }
}
