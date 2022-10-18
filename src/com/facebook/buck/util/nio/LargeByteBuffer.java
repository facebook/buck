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

import com.facebook.buck.util.Scope;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Provides a ByteBuffer-like object for dealing with very large files. */
public class LargeByteBuffer {
  public static LargeByteBuffer with(MappableByteBufferHolder byteBufferHolder) throws IOException {
    return new LargeByteBuffer(byteBufferHolder);
  }

  public static LargeByteBuffer withBytes(byte[] bytes) throws IOException {
    return withByteBuffer(ByteBuffer.wrap(bytes));
  }

  public static LargeByteBuffer withByteBuffer(ByteBuffer buffer) throws IOException {
    return withByteBuffer(buffer, Integer.MAX_VALUE);
  }

  public static LargeByteBuffer withByteBuffer(ByteBuffer buffer, int maxBufferSize)
      throws IOException {
    return new LargeByteBuffer(
        new MappableByteBufferHolder(buffer.limit(), maxBufferSize) {
          @Override
          protected ByteBuffer map(long start, int length) throws IOException {
            assert start > 0;
            assert length > 0;
            assert start + length <= buffer.limit();

            return buffer.position((int) start).slice().limit(length);
          }
        });
  }

  public static Scoped withFileChannel(FileChannel fileChannel) throws IOException {
    return withFileChannel(fileChannel, Integer.MAX_VALUE);
  }

  public static Scoped withFileChannel(FileChannel fileChannel, int maxBufferSize)
      throws IOException {
    return withFileChannel(fileChannel, maxBufferSize, FileChannel.MapMode.READ_WRITE);
  }

  public static Scoped withReadOnlyFileChannel(FileChannel fileChannel) throws IOException {
    return withReadOnlyFileChannel(fileChannel, Integer.MAX_VALUE);
  }

  public static Scoped withReadOnlyFileChannel(FileChannel fileChannel, int maxBufferSize)
      throws IOException {
    return withFileChannel(fileChannel, maxBufferSize, FileChannel.MapMode.READ_ONLY);
  }

  private static Scoped withFileChannel(
      FileChannel fileChannel, int maxBufferSize, FileChannel.MapMode mode) throws IOException {
    return new Scoped(
        new MappableByteBufferHolder(fileChannel.size(), maxBufferSize) {
          @Override
          protected ByteBuffer map(long start, int length) throws IOException {
            return fileChannel.map(mode, start, length);
          }
        });
  }

  /** Scoped LargeByteBuffer. You must call close() when finished. */
  public static final class Scoped extends LargeByteBuffer implements Scope {
    private Scoped(MappableByteBufferHolder byteBufferHolder) throws IOException {
      super(byteBufferHolder);
    }

    /** Closes the MappableByteBufferHolder's underlying ByteBuffer. */
    @Override
    public void close() {
      byteBufferHolder.releaseByteBufferMemory();
    }
  }

  /** The class that can hand us temporary ByteBuffers to work on. */
  final MappableByteBufferHolder byteBufferHolder;

  /** The offset in the overall byteBufferHolder that this slice represents. */
  private final long sliceOffset;

  /** The current position of the buffer, zero-indexed to the original buffer. */
  private long position;

  /** The end of this slice of data, zero-indexed to the original buffer. */
  private long limit;

  @VisibleForTesting
  LargeByteBuffer(MappableByteBufferHolder byteBufferHolder) throws IOException {
    this(byteBufferHolder, 0);
  }

  LargeByteBuffer(MappableByteBufferHolder byteBufferHolder, long sliceOffset) throws IOException {
    this.byteBufferHolder = byteBufferHolder;
    this.sliceOffset = sliceOffset;
    this.position = sliceOffset;
    this.limit = byteBufferHolder.getSize();

    if (sliceOffset < 0) {
      throw new IOException("Trying to slice outside the bounds of the data");
    }
  }

  /** See ByteBuffer#order. */
  public void order(ByteOrder byteOrder) {
    byteBufferHolder.order(byteOrder);
  }

  /** See ByteBuffer#order. */
  public ByteOrder order() {
    return byteBufferHolder.order();
  }

  /** See ByteBuffer#position. */
  public long position() {
    return position - sliceOffset;
  }

  /** See ByteBuffer#position. */
  public LargeByteBuffer position(long newPosition) {
    position = newPosition + sliceOffset;
    return this;
  }

  /** See ByteBuffer#limit. */
  public long limit() {
    return limit - sliceOffset;
  }

  /** See ByteBuffer#limit. */
  public LargeByteBuffer limit(long newLimit) {
    limit = newLimit + sliceOffset;
    return this;
  }

  /** See ByteBuffer#remaining. */
  public long remaining() {
    return limit - position;
  }

  /** See ByteBuffer#hasRemaining. */
  public boolean hasRemaining() {
    return remaining() > 0;
  }

  /** See ByteBuffer#remaining. */
  public LargeByteBuffer rewind() {
    return position(0);
  }

  /** See ByteBuffer#slice. */
  public LargeByteBuffer duplicate() throws IOException {
    return new LargeByteBuffer(byteBufferHolder, sliceOffset).position(position()).limit(limit());
  }

  /** See ByteBuffer#slice. */
  public LargeByteBuffer slice() throws IOException {
    return slice(0);
  }

  /** Helper for slicing with a given offset. */
  public LargeByteBuffer slice(long offset) throws IOException {
    return new LargeByteBuffer(byteBufferHolder, position + offset);
  }

  /** Helper for slicing with a given offset and limit. */
  public LargeByteBuffer slice(long offset, long limit) throws IOException {
    return new LargeByteBuffer(byteBufferHolder, position + offset).limit(limit);
  }

  /**
   * Checks that the MappableByteBufferHolder has all of the requested data in memory, updates the
   * current position to reflect that the data about to be read has been read, and returns the index
   * in the ByteBuffer to read the data at.
   */
  private int checkWindowGetIndexAndAdvance(long size) throws IOException {
    byteBufferHolder.updateWindow(position, size);
    int result = byteBufferHolder.getRelativeIndex(position);
    position += size;
    return result;
  }

  /**
   * Checks that the MappableByteBufferHolder has all of the requested data in memory and returns
   * the index in the ByteBuffer to read the data at.
   */
  private int checkWindowAndGetIndex(long index, long size) throws IOException {
    byteBufferHolder.updateWindow(index + sliceOffset, size);
    return byteBufferHolder.getRelativeIndex(index + sliceOffset);
  }

  // ==== byte ====

  /** See ByteBuffer#get. */
  public byte get() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(1);
    return byteBufferHolder.getByteBuffer().get(bufferIndex);
  }

  public byte get(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 1);
    return byteBufferHolder.getByteBuffer().get(bufferIndex);
  }

  /** See ByteBuffer#put. */
  public LargeByteBuffer put(byte value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(1);
    byteBufferHolder.getByteBuffer().put(bufferIndex, value);
    return this;
  }

  /** See ByteBuffer#put. */
  public LargeByteBuffer put(long index, byte value) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 1);
    byteBufferHolder.getByteBuffer().put(bufferIndex, value);
    return this;
  }

  // ==== byte[] ====

  /** See ByteBuffer#get. */
  public LargeByteBuffer get(byte[] dst) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(dst.length);
    byteBufferHolder.getByteBuffer().position(bufferIndex).get(dst);
    return this;
  }

  /** See ByteBuffer#put. */
  public LargeByteBuffer put(byte[] value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(value.length);
    byteBufferHolder.getByteBuffer().duplicate().position(bufferIndex).put(value);
    return this;
  }

  // ==== short ====

  /** See ByteBuffer#getShort. */
  public short getShort() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(2);
    return byteBufferHolder.getByteBuffer().getShort(bufferIndex);
  }

  /** See ByteBuffer#getShort. */
  public short getShort(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 2);
    return byteBufferHolder.getByteBuffer().getShort(bufferIndex);
  }

  /** See ByteBuffer#putShort. */
  public LargeByteBuffer putShort(short value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(2);
    byteBufferHolder.getByteBuffer().putShort(bufferIndex, value);
    return this;
  }

  /** See ByteBuffer#putShort. */
  public LargeByteBuffer putShort(long index, short value) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 2);
    byteBufferHolder.getByteBuffer().putShort(bufferIndex, value);
    return this;
  }

  // ==== int ====

  /** See ByteBuffer#getInt. */
  public int getInt() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(4);
    return byteBufferHolder.getByteBuffer().getInt(bufferIndex);
  }

  /** See ByteBuffer#getInt. */
  public int getInt(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 4);
    return byteBufferHolder.getByteBuffer().getInt(bufferIndex);
  }

  /** See ByteBuffer#putShort. */
  public LargeByteBuffer putInt(int value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(4);
    byteBufferHolder.getByteBuffer().putInt(bufferIndex, value);
    return this;
  }

  /** See ByteBuffer#putInt. */
  public LargeByteBuffer putInt(long index, int value) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 4);
    byteBufferHolder.getByteBuffer().putInt(bufferIndex, value);
    return this;
  }

  // ==== long ====

  /** See ByteBuffer#getLong. */
  public long getLong() throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(8);
    return byteBufferHolder.getByteBuffer().getLong(bufferIndex);
  }

  /** See ByteBuffer#getLong. */
  public long getLong(long index) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 8);
    return byteBufferHolder.getByteBuffer().getLong(bufferIndex);
  }

  /** See ByteBuffer#putLong. */
  public LargeByteBuffer putLong(long value) throws IOException {
    int bufferIndex = checkWindowGetIndexAndAdvance(8);
    byteBufferHolder.getByteBuffer().putLong(bufferIndex, value);
    return this;
  }

  /** See ByteBuffer#putInt. */
  public LargeByteBuffer putLong(long index, long value) throws IOException {
    int bufferIndex = checkWindowAndGetIndex(index, 8);
    byteBufferHolder.getByteBuffer().putLong(bufferIndex, value);
    return this;
  }
}
