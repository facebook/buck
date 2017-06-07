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

public class LinkEditDataCommandUtils {
  private LinkEditDataCommandUtils() {}

  public static LinkEditDataCommand createFromBuffer(ByteBuffer buffer) {
    return LinkEditDataCommand.of(
        LoadCommandCommonFieldsUtils.createFromBuffer(buffer),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  public static void writeCommandToBuffer(LinkEditDataCommand command, ByteBuffer buffer) {
    LoadCommandCommonFieldsUtils.writeCommandToBuffer(command.getLoadCommandCommonFields(), buffer);
    buffer.putInt(command.getDataoff().intValue()).putInt(command.getDatasize().intValue());
  }

  public static void updateLinkEditDataCommand(
      ByteBuffer buffer, LinkEditDataCommand old, LinkEditDataCommand updated) {
    Preconditions.checkArgument(
        old.getLoadCommandCommonFields().getOffsetInBinary()
            == updated.getLoadCommandCommonFields().getOffsetInBinary());
    buffer.position(updated.getLoadCommandCommonFields().getOffsetInBinary());
    writeCommandToBuffer(updated, buffer);
  }

  public static int alignCodeSignatureOffsetValue(int value) {
    /**
     * Code signature offset is expected to be aligned by 16 bytes:
     * https://opensource.apple.com/source/cctools/cctools-782/libstuff/checkout.c (look for "code
     * signature data out of place" error)
     */
    int alignment = 15;
    value += alignment;
    value &= ~alignment;
    return value;
  }
}
