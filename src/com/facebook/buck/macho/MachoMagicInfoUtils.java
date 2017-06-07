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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MachoMagicInfoUtils {
  private MachoMagicInfoUtils() {}

  /**
   * Reads 4 bytes from the given byte buffer from position 0 and produces the MachoMagicInfo object
   * which describes basic information about Mach Object file.
   *
   * @param buffer Byte Buffer which holds bytes for the mach header magic number.
   * @return MachoMagicInfo object.
   * @throws IOException
   */
  public static MachoMagicInfo getMachMagicInfo(ByteBuffer buffer) {
    ByteOrder order = buffer.order();
    UnsignedInteger magic =
        UnsignedInteger.fromIntBits(buffer.order(ByteOrder.BIG_ENDIAN).getInt());
    buffer.order(order);
    return new MachoMagicInfo(magic);
  }
}
