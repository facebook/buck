// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.dex.DexFile;
import com.android.tools.r8.dex.DexOutputBuffer;

public class EncodedValueUtils {

  public static long parseSigned(DexFile file, int numberOfBytes) {
    assert numberOfBytes > 0;
    long result = 0;
    int shift = 0;
    for (int i = 1; i < numberOfBytes; i++) {
      result |= ((long) (file.get() & 0xFF)) << shift;
      shift += 8;
    }
    // Let the last byte sign-extend into any remaining bytes.
    return result | (((long) file.get()) << shift);
  }

  // Inspired by com.android.dex.EncodedValueCodec
  public static int putSigned(DexOutputBuffer outputBuffer, long value, int expectedSize) {
    int bit_size = Long.SIZE + 1 - Long.numberOfLeadingZeros(value ^ (value >> (Long.SIZE - 1)));
    int size = Math.max((bit_size + Byte.SIZE - 1) / Byte.SIZE, 1);
    assert size > 0 && size <= expectedSize;
    for (int i = 0; i < size; i++) {
      outputBuffer.putByte((byte) value);
      value >>= Byte.SIZE;
    }
    return size;
  }

  // Inspired by com.android.dex.EncodedValueCodec
  public static byte[] encodeSigned(long value) {
    int bit_size = Long.SIZE + 1 - Long.numberOfLeadingZeros(value ^ (value >> (Long.SIZE - 1)));
    int size = Math.max((bit_size + Byte.SIZE - 1) / Byte.SIZE, 1);
    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) {
      result[i] = (byte) value;
      value >>= Byte.SIZE;
    }
    return result;
  }

  public static long parseUnsigned(DexFile file, int numberOfBytes) {
    assert numberOfBytes > 0;
    long result = 0;
    int shift = 0;
    for (int i = 0; i < numberOfBytes; i++) {
      result |= ((long) (file.get() & 0xFF)) << shift;
      shift += 8;
    }
    return result;
  }

  // Inspired by com.android.dex.EncodedValueCodec
  public static int putUnsigned(DexOutputBuffer outputBuffer, long value,
      int expectedSize) {
    int bit_size = Long.SIZE - Long.numberOfLeadingZeros(value);
    int size = Math.max((bit_size + Byte.SIZE - 1) / Byte.SIZE, 1);
    assert size > 0 && size <= expectedSize;
    for (int i = 0; i < size; i++) {
      outputBuffer.putByte((byte) value);
      value >>= Byte.SIZE;
    }
    return size;
  }

  public static byte[] encodeUnsigned(long value) {
    int bit_size = Long.SIZE - Long.numberOfLeadingZeros(value);
    int size = Math.max((bit_size + Byte.SIZE - 1) / Byte.SIZE, 1);
    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) {
      result[i] = (byte) value;
      value >>= Byte.SIZE;
    }
    return result;
  }

  public static int putBitsFromRightZeroExtended(DexOutputBuffer outputBuffer, long value,
      int expectedSize) {
    int bit_size = Long.SIZE - Long.numberOfTrailingZeros(value);
    int size = (bit_size - 1) / Byte.SIZE + 1;
    assert size > 0 && size <= expectedSize;
    value >>= Long.SIZE - (size * Byte.SIZE); // shift trailing zeros.
    for (int i = 0; i < size; i++) {
      outputBuffer.putByte((byte) value);
      value >>= Byte.SIZE;
    }
    return size;
  }

  private static byte[] encodeBitsFromRightZeroExtended(long value) {
    int bit_size = Long.SIZE - Long.numberOfTrailingZeros(value);
    int size = (bit_size - 1) / Byte.SIZE + 1;
    value >>= Long.SIZE - (size * Byte.SIZE); // shift trailing zeros.
    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) {
      result[i] = (byte) value;
      value >>= Byte.SIZE;
    }
    return result;
  }

  public static float parseFloat(DexFile file, int numberOfBytes) {
    long bits = parseUnsigned(file, numberOfBytes) << ((Float.BYTES - numberOfBytes) * Byte.SIZE);
    return Float.intBitsToFloat((int) bits);
  }

  public static int putFloat(DexOutputBuffer outputBuffer, float value) {
    long bits = ((long) Float.floatToIntBits(value)) << 32;
    return EncodedValueUtils.putBitsFromRightZeroExtended(outputBuffer, bits, Float.BYTES);
  }

  public static byte[] encodeFloat(float value) {
    long tmp = ((long) Float.floatToIntBits(value)) << 32;
    byte[] result = EncodedValueUtils.encodeBitsFromRightZeroExtended(tmp);
    assert result.length <= Float.BYTES;
    return result;
  }

  public static double parseDouble(DexFile file, int numberOfBytes) {
    long bits = parseUnsigned(file, numberOfBytes) << ((Double.BYTES - numberOfBytes) * Byte.SIZE);
    return Double.longBitsToDouble(bits);
  }

  public static int putDouble(DexOutputBuffer outputBuffer, double value) {
    long bits = Double.doubleToLongBits(value);
    return EncodedValueUtils.putBitsFromRightZeroExtended(outputBuffer, bits, Double.BYTES);
  }

  public static byte[] encodeDouble(double value) {
    long tmp = Double.doubleToLongBits(value);
    byte[] result = EncodedValueUtils.encodeBitsFromRightZeroExtended(tmp);
    assert result.length <= Double.BYTES;
    return result;
  }

}

