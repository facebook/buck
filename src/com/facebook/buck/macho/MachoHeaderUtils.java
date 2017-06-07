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
import java.nio.ByteBuffer;
import java.util.Optional;

public class MachoHeaderUtils {

  private MachoHeaderUtils() {}

  public static final int MAGIC_SIZE = 4;

  public static int getHeaderSize(MachoHeader machoHeader) {
    return machoHeader.getMagic().equals(MachoHeader.MH_MAGIC_64)
        ? MachoHeader.MACH_HEADER_SIZE_64
        : MachoHeader.MACH_HEADER_SIZE_32;
  }

  public static MachoHeader create32BitFromBuffer(ByteBuffer buffer) {
    return MachoHeader.of(
        UnsignedInteger.fromIntBits(buffer.getInt()),
        buffer.getInt(),
        buffer.getInt(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        Optional.empty());
  }

  public static MachoHeader create64BitFromBuffer(ByteBuffer buffer) {
    return MachoHeader.of(
        UnsignedInteger.fromIntBits(buffer.getInt()),
        buffer.getInt(),
        buffer.getInt(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        Optional.of(UnsignedInteger.fromIntBits(buffer.getInt())));
  }

  /**
   * Reads the Mach Header from the given buffer from current position.
   *
   * @param buffer Buffer that holds the data of the mach header
   * @return MachoHeader for 32 or 64 bit Mach object.
   */
  public static MachoHeader createFromBuffer(ByteBuffer buffer) {
    int position = buffer.position();
    UnsignedInteger magic = UnsignedInteger.fromIntBits(buffer.getInt());
    buffer.position(position);
    Preconditions.checkArgument(
        magic.equals(MachoHeader.MH_MAGIC) || magic.equals(MachoHeader.MH_MAGIC_64));

    if (magic.equals(MachoHeader.MH_MAGIC_64)) {
      return create64BitFromBuffer(buffer);
    } else {
      return create32BitFromBuffer(buffer);
    }
  }
}
