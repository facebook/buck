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

package com.facebook.buck.cxx.toolchain.objectfile;

import java.nio.ByteBuffer;

/** Utility for ULEB128 encoding as used by ld64. */
public class ULEB128 {
  /**
   * Computes the size when encoding values using ULEB128.
   *
   * @param value The value for which to return the size of in ULEB128.
   * @return The number of bytes needed to encode {@param value}.
   */
  public static int size(long value) {
    int groupCount = 0;
    long valueToSize = value;

    do {
      groupCount++;
      valueToSize >>>= 7;
    } while (valueToSize != 0);

    return groupCount;
  }

  /**
   * Encodes a value using ULEB128 encoding.
   *
   * @param byteBuffer The output destination used for encoding.
   * @param value The value to encode.
   * @return Number of bytes written.
   */
  public static int write(ByteBuffer byteBuffer, long value) {
    long valueToWrite = value;
    int bytesWritten = 0;

    do {
      byte groupValue = (byte) (valueToWrite & 0x7F);
      valueToWrite >>>= 7;
      if (valueToWrite != 0) {
        groupValue |= 0x80;
      }

      byteBuffer.put(groupValue);
      bytesWritten++;
    } while (valueToWrite != 0);

    return bytesWritten;
  }

  /**
   * Reads a number encoded as ULEB128. If the encoded number exceeds 64bits, then a {@code
   * java.lang.IllegalStateException} exception is thrown because {@code long} stores a maximum of
   * 64bits.
   *
   * @param byteBuffer The source which to read from.
   * @return The value of the ULEB128 encoded number.
   */
  public static long read(ByteBuffer byteBuffer) {
    long value = 0;
    int bytesRead = 0;
    boolean continueReading;

    do {
      final byte rawByteValue = byteBuffer.get();
      if (bytesRead == 9 && (rawByteValue & ~0x1) != 0) {
        // "long" can only fit 64bits, so check that the top 7 MSB bits
        // in the 10th byte are all zeroes (9 bytes provide 63 bits of info).
        throw new IllegalStateException("ULEB128 sequence exceeds 64bits");
      }

      value |= (rawByteValue & 0x7FL) << (bytesRead * 7);

      bytesRead++;
      continueReading = ((rawByteValue & 0x80) != 0);
    } while (continueReading);

    return value;
  }
}
