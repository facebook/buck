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

import com.google.common.base.Function;
import com.google.common.primitives.UnsignedInteger;

import java.io.IOException;
import java.nio.ByteBuffer;

public class LoadCommandUtils {
  private LoadCommandUtils() {}

  public static LoadCommand createFromBuffer(ByteBuffer buffer) {
    return LoadCommand.of(
        buffer.position(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  /**
   * This is a kind of umbrella method that returns you LoadCommand object depending on the contents
   * of the given bytes array.
   * @param buffer Buffer which contains at least values for the LoadCommandCommonMethods fields,
   *               positioned at the first byte of the command (cmd field)
   * @return LoadCommand that is suitable to handle the given bytes array.
   */
  public static LoadCommand createLoadCommandFromBuffer(ByteBuffer buffer) throws IOException {
    return createFromBuffer(buffer);
  }


  /**
   * Writes a byte representation of the load command into the given buffer.
   * @param command LoadCommand to write into the buffer.
   * @param buffer ByteBuffer, positioned and prepared to accept new data.
   */
  public static void writeCommandToBuffer(LoadCommand command, ByteBuffer buffer) {
    buffer
        .putInt(command.getCmd().intValue())
        .putInt(command.getCmdsize().intValue());
  }

  /**
   * Enumerates the load commands in the given mach binary which is represented by the buffer
   * by calling the given callback, starting at buffer's position.
   * @param buffer The buffer which holds all data.
   * @param callback The Function object which should be called on each LoadCommand enumeration
   *                 event. The argument of the function is the LoadCommand object.
   *                 If Function returns Boolean.TRUE then enumeration will continue;
   *                 otherwise enumeration will stop and callback will not be called anymore.
   * @throws IOException
   */
  public static void enumerateLoadCommandsInFile(
      ByteBuffer buffer,
      Function<LoadCommand, Boolean> callback) throws IOException {
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    int firstCommandOffset = MachoHeaderUtils.getHeaderSize(header);
    int relativeCommandOffset = 0;
    for (int i = 0; i < header.getNcmds().intValue(); i++) {
      buffer.position(firstCommandOffset + relativeCommandOffset);
      LoadCommand command = LoadCommandUtils.createLoadCommandFromBuffer(buffer);
      if (!callback.apply(command).booleanValue()) {
        break;
      }
      relativeCommandOffset += command.getCmdsize().intValue();
    }
  }
}
