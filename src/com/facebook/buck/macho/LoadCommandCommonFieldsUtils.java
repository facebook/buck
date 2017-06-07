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

public class LoadCommandCommonFieldsUtils {
  private LoadCommandCommonFieldsUtils() {}

  public static LoadCommandCommonFields createFromBuffer(ByteBuffer buffer) {
    return LoadCommandCommonFields.of(
        buffer.position(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  /**
   * Writes a byte representation of the load command into the given buffer.
   *
   * @param command LoadCommandCommonFields to write into the buffer.
   * @param buffer ByteBuffer, positioned and prepared to accept new data.
   */
  public static void writeCommandToBuffer(LoadCommandCommonFields command, ByteBuffer buffer) {
    buffer.putInt(command.getCmd().intValue()).putInt(command.getCmdsize().intValue());
  }
}
