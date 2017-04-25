/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.macho;

import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.io.IOException;
import java.nio.ByteBuffer;

public class NlistUtils {

  private NlistUtils() {}

  public static int getSizeInBytes(boolean is64Bit) {
    if (is64Bit) {
      return Nlist.SIZE_IN_BYTES_64_BIT;
    } else {
      return Nlist.SIZE_IN_BYTES_32_BIT;
    }
  }

  /**
   * Reads Nlist entry for the given architecture from the buffer at the current position.
   *
   * @param buffer ByteBuffer with data, must be positioned properly.
   * @param is64Bit Indicator if architecture is 32 or 64 bits.
   * @return Nlist entry
   */
  public static Nlist createFromBuffer(ByteBuffer buffer, boolean is64Bit) {
    return Nlist.of(
        buffer.position(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.get() & 0xFF),
        UnsignedInteger.fromIntBits(buffer.get() & 0xFF),
        UnsignedInteger.fromIntBits(buffer.getShort() & 0xFFFF),
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL));
  }

  /**
   * Writes the given Nlist to the buffer at the current position.
   *
   * @param nlist The Nlist instance to write to the buffer.
   * @param buffer ByteBuffer with data, must be positioned/resized properly.
   * @param is64Bit Indicator if architecture is 32 or 64 bits.
   */
  public static void writeNlistToBuffer(Nlist nlist, ByteBuffer buffer, boolean is64Bit) {
    buffer
        .putInt(nlist.getN_strx().intValue())
        .put(nlist.getN_type().byteValue())
        .put(nlist.getN_sect().byteValue())
        .putShort(nlist.getN_desc().shortValue());
    if (is64Bit) {
      buffer.putLong(nlist.getN_value().longValue());
    } else {
      buffer.putInt(nlist.getN_value().intValue());
    }
  }

  /**
   * Takes existing Nlist entry and updates it with the new entry.
   *
   * @param buffer The buffer which holds all data.
   * @param original existing Nlist entry that needs to be updated in the buffer.
   * @param updated new Nlist entry that should replace old entry.
   * @param is64Bit Indicator if architecture is 32 or 64 bits.
   * @throws IOException
   */
  public static void updateNlistEntry(
      ByteBuffer buffer, Nlist original, Nlist updated, boolean is64Bit) {
    Preconditions.checkArgument(original.getOffsetInBinary() == updated.getOffsetInBinary());
    buffer.position(updated.getOffsetInBinary());
    writeNlistToBuffer(updated, buffer, is64Bit);
  }
}
