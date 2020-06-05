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

import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Utility class to read the dependent libraries from Mach-O executables. */
public class MachoDependentLibraryReader {

  // See https://opensource.apple.com/source/cctools/cctools-921/include/mach-o/loader.h
  private static final int LC_LOAD_DYLIB = 0xc;
  private static final int LC_LAZY_LOAD_DYLIB = 0x20;
  private static final int LC_LOAD_UPWARD_DYLIB = (0x23 | Machos.LC_REQ_DYLD);
  private static final int LC_LOAD_WEAK_DYLIB = (0x18 | Machos.LC_REQ_DYLD);
  private static final int LC_REEXPORT_DYLIB = (0x1f | Machos.LC_REQ_DYLD);

  /**
   * Returns the list of dependent libraries for a Mach-O executable. The returned list can be used
   * to map library ordinals (1-based indexing) found in the data sections indexed by
   * LC_DYLD_INFO[_ONLY].
   *
   * <p>Note that the position of machoFileBuffer will be left in an undefined state after the
   * method returns. Furthermore, this method does not assume any particular state of the position
   * and will reset it to 0 to read from the beginning of the Mach-O file.
   */
  public static ImmutableList<String> getDependentLibraries(ByteBuffer machoFileBuffer)
      throws Machos.MachoException {
    machoFileBuffer.rewind();
    MachoHeader header = Machos.getHeader(machoFileBuffer);

    // The order of dependent libraries is defined to be the same as the load commands
    // which reference such dynamic libraries.
    ImmutableList.Builder<String> libraries = ImmutableList.builder();

    for (int i = 0, commandsCount = header.getCommandsCount(); i < commandsCount; i++) {
      int commandOffset = machoFileBuffer.position();

      int command = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);
      int commandSize = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

      Optional<String> maybeLibrary =
          extractDependentLibraryFromCommand(machoFileBuffer, command, commandOffset, commandSize);
      maybeLibrary.ifPresent(library -> libraries.add(library));

      // Seek to the beginning of the next command
      machoFileBuffer.position(commandOffset + commandSize);
    }

    return libraries.build();
  }

  private static Optional<String> extractDependentLibraryFromCommand(
      ByteBuffer machoFileBuffer, int command, int commandOffset, int commandSize) {
    // While LC_ID_DYLIB is technically a dylib load command, it does not participate in the
    // list of dependent libraries as it defines the metadata for the current Mach-O dylib.
    switch (command) {
      case LC_LOAD_DYLIB:
      case LC_LAZY_LOAD_DYLIB:
      case LC_LOAD_UPWARD_DYLIB:
      case LC_LOAD_WEAK_DYLIB:
      case LC_REEXPORT_DYLIB:
        int stringOffset = ObjectFileScrubbers.getLittleEndianInt(machoFileBuffer);

        // Create a temporary ByteBuffer to protect against missing NULL characters and reading
        // past the current command.
        machoFileBuffer.position(commandOffset + stringOffset);
        int maxSize = (commandOffset + commandSize) - machoFileBuffer.position();
        ByteBuffer stringBuffer = machoFileBuffer.slice();
        stringBuffer.limit(maxSize);

        String libName = ObjectFileScrubbers.decodeCString(stringBuffer);
        return Optional.of(libName);
    }

    return Optional.empty();
  }
}
