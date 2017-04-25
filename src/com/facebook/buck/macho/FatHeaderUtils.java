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

import com.google.common.primitives.UnsignedInteger;
import java.nio.ByteBuffer;

public class FatHeaderUtils {
  private FatHeaderUtils() {}

  public static FatHeader createFromBuffer(ByteBuffer buffer) {
    return FatHeader.of(
        UnsignedInteger.fromIntBits(buffer.getInt()), UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  public static boolean isFatHeaderMagic(UnsignedInteger magic) {
    return magic.equals(FatHeader.FAT_MAGIC) || magic.equals(FatHeader.FAT_CIGAM);
  }

  public static boolean isLittleEndian(UnsignedInteger magic) {
    return magic.equals(FatHeader.FAT_CIGAM);
  }
}
