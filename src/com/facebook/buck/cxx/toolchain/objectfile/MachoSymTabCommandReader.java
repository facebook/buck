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

/** Reader for the LC_SYMTAB command */
public class MachoSymTabCommandReader {

  /**
   * Reads the LC_SYMTAB command from the Mach-O file.
   *
   * @param machoFileBuffer The byte buffer representing the file. The method rewinds the position.
   * @return If the command exist, a {@code MachoSymTabCommand}, otherwise an empty optional on
   *     failure.
   */
  public static Optional<MachoSymTabCommand> read(MappedByteBuffer machoFileBuffer) {

    machoFileBuffer.rewind();
    try {
      MachoHeader header = Machos.getHeader(machoFileBuffer);
      int commandsCount = header.getCommandsCount();

      for (int i = 0; i < commandsCount; i++) {
        int command = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
        int commandSize = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
        if (Machos.LC_SYMTAB == command) {
          // https://opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/loader.h
          //
          // struct symtab_command {
          //   uint32_t  cmd;      // LC_SYMTAB
          //   uint32_t  cmdsize;  // sizeof(struct symtab_command)
          //   uint32_t  symoff;   // symbol table offset
          //   uint32_t  nsyms;    // number of symbol table entries
          //   uint32_t  stroff;   // string table offset
          //   uint32_t  strsize;  // string table size in bytes
          // };
          int symoff = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int nsyms = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int stroff = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          int strsize = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
          return Optional.of(
              ImmutableMachoSymTabCommand.of(command, commandSize, symoff, nsyms, stroff, strsize));
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
