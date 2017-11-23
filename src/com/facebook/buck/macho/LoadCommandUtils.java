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

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedInteger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class LoadCommandUtils {
  private LoadCommandUtils() {}

  /**
   * This is a kind of umbrella method that returns you LoadCommand object depending on the contents
   * of the given bytes array.
   *
   * @param buffer Buffer which contains at least values for the LoadCommand fields, positioned at
   *     the first byte of the command (cmd field)
   * @return LoadCommandCommonFields that is suitable to handle the given bytes array.
   */
  public static LoadCommand createLoadCommandFromBuffer(
      ByteBuffer buffer, NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder) {
    int position = buffer.position();
    UnsignedInteger cmd = UnsignedInteger.fromIntBits(buffer.getInt());
    buffer.position(position);

    if (SegmentCommand.VALID_CMD_VALUES.contains(cmd)) {
      return SegmentCommandUtils.createFromBuffer(buffer, nulTerminatedCharsetDecoder);
    } else if (cmd.equals(SymTabCommand.LC_SYMTAB)) {
      return SymTabCommandUtils.createFromBuffer(buffer);
    } else if (cmd.equals(UUIDCommand.LC_UUID)) {
      return UUIDCommandUtils.createFromBuffer(buffer);
    } else if (LinkEditDataCommand.VALID_CMD_VALUES.contains(cmd)) {
      return LinkEditDataCommandUtils.createFromBuffer(buffer);
    } else {
      return UnknownCommandUtils.createFromBuffer(buffer);
    }
  }

  /**
   * Enumerates the load commands in the given mach binary which is represented by the buffer by
   * calling the given callback, starting at buffer's position.
   *
   * @param buffer The buffer which holds all data.
   * @param callback The Function object which should be called on each LoadCommand enumeration
   *     event. The argument of the function is the LoadCommand object. If Function returns
   *     Boolean.TRUE then enumeration will continue; otherwise enumeration will stop and callback
   *     will not be called anymore.
   * @throws IOException
   */
  public static void enumerateLoadCommandsInFile(
      ByteBuffer buffer,
      NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder,
      Function<LoadCommand, Boolean> callback) {
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    int firstCommandOffset = MachoHeaderUtils.getHeaderSize(header);
    int relativeCommandOffset = 0;
    for (int i = 0; i < header.getNcmds().intValue(); i++) {
      buffer.position(firstCommandOffset + relativeCommandOffset);
      LoadCommand command =
          LoadCommandUtils.createLoadCommandFromBuffer(buffer, nulTerminatedCharsetDecoder);
      if (!callback.apply(command)) {
        break;
      }
      relativeCommandOffset += command.getLoadCommandCommonFields().getCmdsize().intValue();
    }
  }

  /**
   * Finds all load commands with the given type in the buffer starting at the buffer's position.
   * Example usage is:
   *
   * <p>ImmutableList<MyLoadCommand> results = findLoadCommandsWithClass(buffer,
   * MyLoadCommand.class);
   *
   * @param buffer The buffer which holds all data.
   * @param type Load command's class, like SomeLoadCommand.class.
   * @param <T> Return type of the load command, like SomeLoadCommand.
   * @return List with all load commands of the given type.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public static <T extends LoadCommand> ImmutableList<T> findLoadCommandsWithClass(
      ByteBuffer buffer,
      NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder,
      final Class<T> type) {
    final List<T> results = new ArrayList<>();
    enumerateLoadCommandsInFile(
        buffer,
        nulTerminatedCharsetDecoder,
        input -> {
          if (type.isInstance(input)) {
            results.add((T) input);
          }
          return true;
        });
    return ImmutableList.copyOf(results);
  }
}
