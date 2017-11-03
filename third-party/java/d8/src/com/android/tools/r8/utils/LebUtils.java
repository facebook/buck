// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.dex.BaseFile;
import com.android.tools.r8.dex.DexOutputBuffer;
import java.util.Arrays;

public class LebUtils {
  private static final int BITS_PER_ENCODED_BYTE = 7;
  private static final int PAYLOAD_MASK = 0x7f;
  private static final int MORE_DATA_TAG_BIT = 0x80;
  private static final int MAX_BYTES_PER_VALUE = 5;

  public static int parseUleb128(BaseFile file) {
    int result = 0;
    byte b;
    int shift = 0;
    do {
      b = file.get();
      result |= (b & (byte) PAYLOAD_MASK) << shift;
      shift += BITS_PER_ENCODED_BYTE;
    } while ((b & ~(byte) PAYLOAD_MASK) == ~(byte) PAYLOAD_MASK);
    assert shift <= MAX_BYTES_PER_VALUE * BITS_PER_ENCODED_BYTE;  // At most five bytes are used.
    assert result >= 0;  // Ensure the java int didn't overflow.
    return result;
  }

  // Inspired by com.android.dex.Leb128.java
  public static byte[] encodeUleb128(int value) {
    byte result[] = new byte[MAX_BYTES_PER_VALUE];
    int remaining = value >>> BITS_PER_ENCODED_BYTE;
    int bytes = 0;
    while (remaining != 0) {
      result[bytes++] = (byte) ((value & PAYLOAD_MASK) | MORE_DATA_TAG_BIT);
      value = remaining;
      remaining >>>= BITS_PER_ENCODED_BYTE;
    }
    result[bytes++] = (byte) (value & PAYLOAD_MASK);
    return Arrays.copyOf(result, bytes);
  }

  // Inspired by com.android.dex.Leb128.java
  public static void putUleb128(DexOutputBuffer outputBuffer, int value) {
    int remaining = value >>> BITS_PER_ENCODED_BYTE;
    while (remaining != 0) {
      outputBuffer.putByte((byte) ((value & PAYLOAD_MASK) | MORE_DATA_TAG_BIT));
      value = remaining;
      remaining >>>= BITS_PER_ENCODED_BYTE;
    }
    outputBuffer.putByte((byte) (value & PAYLOAD_MASK));
  }

  public static int sizeAsUleb128(int value) {
    return Math
        .max(1, (Integer.SIZE - Integer.numberOfLeadingZeros(value) + 6) / BITS_PER_ENCODED_BYTE);
  }

  public static int parseSleb128(BaseFile file) {
    int result = 0;
    byte b;
    int shift = 0;
    do {
      b = file.get();
      result |= (b & (byte) PAYLOAD_MASK) << shift;
      shift += BITS_PER_ENCODED_BYTE;
    } while ((b & ~(byte) PAYLOAD_MASK) == ~(byte) PAYLOAD_MASK);
    int mask = 1 << (shift - 1);
    assert shift <= MAX_BYTES_PER_VALUE * BITS_PER_ENCODED_BYTE;  // At most five bytes are used.
    return (result ^ mask) - mask;
  }

  // Inspired by com.android.dex.Leb128.java
  public static byte[] encodeSleb128(int value) {
    byte result[] = new byte[MAX_BYTES_PER_VALUE];
    int remaining = value >> BITS_PER_ENCODED_BYTE;
    boolean hasMore = true;
    int end = value >= 0 ? 0 : -1;
    int bytes = 0;
    while (hasMore) {
      hasMore = (remaining != end)
          || ((remaining & 1) != ((value >> 6) & 1));
      result[bytes++] = (byte) ((value & PAYLOAD_MASK) | (hasMore ? MORE_DATA_TAG_BIT : 0));
      value = remaining;
      remaining >>= BITS_PER_ENCODED_BYTE;
    }
    return Arrays.copyOf(result, bytes);
  }

  // Inspired by com.android.dex.Leb128.java
  public static void putSleb128(DexOutputBuffer outputBuffer, int value) {
    int remaining = value >> BITS_PER_ENCODED_BYTE;
    boolean hasMore = true;
    int end = ((value & Integer.MIN_VALUE) == 0) ? 0 : -1;
    while (hasMore) {
      hasMore = (remaining != end)
          || ((remaining & 1) != ((value >> 6) & 1));
      outputBuffer.putByte((byte) ((value & PAYLOAD_MASK) | (hasMore ? MORE_DATA_TAG_BIT : 0)));
      value = remaining;
      remaining >>= BITS_PER_ENCODED_BYTE;
    }
  }

  public static int sizeAsSleb128(int value) {
    if (value < 0) {
      value = ~value;
    }
    // Note the + 7 to account for the extra bit on 7-bit boundaries.
    return (Integer.SIZE - Integer.numberOfLeadingZeros(value) + 7) / BITS_PER_ENCODED_BYTE;
  }

  public static byte[] encodeUleb128p1(int value) {
    return encodeUleb128(value + 1);
  }

  public static byte[][] encodeUleb128p1(int[] values) {
    byte[][] result = new byte[values.length][];
    for (int i = 0; i < result.length; i++) {
      result[i] = encodeUleb128p1(values[i]);
    }
    return result;
  }
}
