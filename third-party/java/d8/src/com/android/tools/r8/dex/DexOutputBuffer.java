// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.utils.EncodedValueUtils;
import com.android.tools.r8.utils.LebUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Provides an abstraction around a {@link ByteBuffer} with write operations for
 * additional DEX specific formats, like Leb128.
 */
public class DexOutputBuffer {
  private static final int DEFAULT_BUFFER_SIZE = 256 * 1024;

  private ByteBuffer byteBuffer;

  public DexOutputBuffer() {
    byteBuffer = allocate(DEFAULT_BUFFER_SIZE);
  }

  private void ensureSpaceFor(int bytes) {
    if (byteBuffer.remaining() < bytes) {
      int newSize = byteBuffer.capacity() + Math.max(byteBuffer.capacity(), bytes * 2);
      ByteBuffer newBuffer = allocate(newSize);
      System.arraycopy(byteBuffer.array(), 0, newBuffer.array(), 0, byteBuffer.position());
      newBuffer.position(byteBuffer.position());
      byteBuffer = newBuffer;
    }
  }

  private ByteBuffer allocate(int size) {
    ByteBuffer buffer = ByteBuffer.allocate(size);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    return buffer;
  }

  public void putUleb128(int value) {
    LebUtils.putUleb128(this, value);
  }

  public void putSleb128(int value) {
    LebUtils.putSleb128(this, value);
  }

  public int putSignedEncodedValue(long value, int expectedSize) {
    return EncodedValueUtils.putSigned(this, value, expectedSize);
  }

  public int putUnsignedEncodedValue(long value, int expectedSize) {
    return EncodedValueUtils.putUnsigned(this, value, expectedSize);
  }

  public void putInstructions(Instruction[] insns, ObjectToOffsetMapping mapping) {
    int size = 0;
    for (Instruction insn : insns) {
      size += insn.getSize();
    }
    ensureSpaceFor(size * Short.BYTES);
    assert byteBuffer.position() % 2 == 0;
    ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
    for (int i = 0; i < insns.length; i++) {
      insns[i].write(shortBuffer, mapping);
    }
    byteBuffer.position(byteBuffer.position() + shortBuffer.position() * Short.BYTES);
  }

  public void putByte(byte aByte) {
    ensureSpaceFor(Byte.BYTES);
    byteBuffer.put(aByte);
  }

  public void putBytes(byte[] bytes) {
    ensureSpaceFor(bytes.length);
    byteBuffer.put(bytes);
  }

  public void putShort(short aShort) {
    ensureSpaceFor(Short.BYTES);
    byteBuffer.putShort(aShort);
  }

  public void putInt(int anInteger) {
    ensureSpaceFor(Integer.BYTES);
    byteBuffer.putInt(anInteger);
  }

  /**
   * Moves the position in the bytebuffer forward until it is aligned.
   *
   * @param bytes  alignment requirement in bytes
   * @return       the new position after alignment
   */
  public int align(int bytes) {
    assert bytes > 0;
    int mask = bytes - 1;
    int newPosition = (byteBuffer.position() + mask) & ~mask;
    ensureSpaceFor(newPosition - position());
    byteBuffer.position(newPosition);
    return newPosition;
  }

  public int position() {
    return byteBuffer.position();
  }

  public void forward(int bytes) {
    ensureSpaceFor(bytes);
    byteBuffer.position(byteBuffer.position() + bytes);
  }

  public void rewind(int bytes) {
    forward(-bytes);
  }

  public void moveTo(int position) {
    ensureSpaceFor(position - byteBuffer.position());
    byteBuffer.position(position);
  }

  public boolean isAligned(int bytes) {
    return position() % bytes == 0;
  }

  public byte[] asArray() {
    return byteBuffer.array();
  }
}
