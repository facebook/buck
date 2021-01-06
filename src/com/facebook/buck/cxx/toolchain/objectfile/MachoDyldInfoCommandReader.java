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

import java.nio.MappedByteBuffer;
import java.util.Optional;

/** Reader for LC_DYLD_INFO[_ONLY] Mach-O commands. */
public class MachoDyldInfoCommandReader {

  /**
   * Reads the LC_DYLD_INFO[_ONLY] commands from a Mach-O file.
   *
   * @param machoFileBuffer The byte buffer representing the file. The method rewinds the position.
   * @return If the command exists, a {@code MachoDyldInfoCommand}, otherwise an empty optional on
   *     failure.
   */
  public static Optional<MachoDyldInfoCommand> read(MappedByteBuffer machoFileBuffer) {

    machoFileBuffer.rewind();
    try {
      MachoHeader header = Machos.getHeader(machoFileBuffer);
      int commandsCount = header.getCommandsCount();

      for (int i = 0; i < commandsCount; i++) {
        int command = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
        int commandSize = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
        if (Machos.LC_DYLD_INFO == command || Machos.LC_DYLD_INFO_ONLY == command) {
          // https://opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/loader.h
          //
          // struct dyld_info_command {
          //     uint32_t   cmd;          // LC_DYLD_INFO or LC_DYLD_INFO_ONLY
          //     uint32_t   cmdsize;      // sizeof(struct dyld_info_command)
          //
          //     uint32_t   rebase_off;  // file offset to rebase info
          //     uint32_t   rebase_size; // size of rebase info
          //
          //     uint32_t   bind_off;    // file offset to binding info
          //     uint32_t   bind_size;   // size of binding info
          //
          //     uint32_t   weak_bind_off;   // file offset to weak binding info
          //     uint32_t   weak_bind_size;  // size of weak binding info
          //
          //     uint32_t   lazy_bind_off;   // file offset to lazy binding info
          //     uint32_t   lazy_bind_size;  // size of lazy binding infs
          //
          //     uint32_t   export_off;  // file offset to lazy binding info
          //     uint32_t   export_size; // size of lazy binding infs
          // };

          int rebase_off = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int rebase_size = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

          int bind_off = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int bind_size = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

          int weak_bind_off = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int weak_bind_size = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

          int lazy_bind_off = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int lazy_bind_size = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

          int export_off = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int export_size = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

          return Optional.of(
              ImmutableMachoDyldInfoCommand.of(
                  command,
                  commandSize,
                  rebase_off,
                  rebase_size,
                  bind_off,
                  bind_size,
                  weak_bind_off,
                  weak_bind_size,
                  lazy_bind_off,
                  lazy_bind_size,
                  export_off,
                  export_size));
        }

        // Skip over command body
        ObjectFileScrubbers.getBytes(machoFileBuffer, commandSize - 8);
      }
    } catch (Machos.MachoException e) {
      // Handle failure by returning Optional
    }

    return Optional.empty();
  }
}
