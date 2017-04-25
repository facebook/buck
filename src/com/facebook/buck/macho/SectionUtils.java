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

import com.facebook.buck.charset.NulTerminatedCharsetDecoder;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Optional;

public class SectionUtils {

  private SectionUtils() {}

  public static int sizeOfSectionHeader(boolean is64Bit) {
    if (is64Bit) {
      return Section.SIZE_IN_BYTES_64_BIT;
    } else {
      return Section.SIZE_IN_BYTES_32_BIT;
    }
  }

  /**
   * Creates a section for the given architecture by reading the provided byte buffer from its
   * current position.
   *
   * @param buffer Buffer with data, must be positioned properly.
   * @param is64Bit Indicator if architecture is 64 or 32 bit.
   * @return Section object
   */
  public static Section createFromBuffer(
      ByteBuffer buffer, boolean is64Bit, NulTerminatedCharsetDecoder decoder)
      throws CharacterCodingException {

    int offset = buffer.position();
    String sectname = decoder.decodeString(buffer);
    buffer.position(offset + Section.LENGTH_OF_STRING_FIELDS_IN_BYTES);
    String segname = decoder.decodeString(buffer);
    buffer.position(offset + Section.LENGTH_OF_STRING_FIELDS_IN_BYTES * 2);
    return Section.of(
        offset,
        sectname,
        segname,
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        is64Bit ? Optional.of(UnsignedInteger.fromIntBits(buffer.getInt())) : Optional.empty());
  }
}
