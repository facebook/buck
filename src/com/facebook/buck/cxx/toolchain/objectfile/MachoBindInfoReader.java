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
import com.google.common.collect.ImmutableSet;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Utility class to read binding info for Mach-O executables. */
public class MachoBindInfoReader {

  // See https://opensource.apple.com/source/cctools/cctools-921/include/mach-o/loader.h

  private static final int BIND_MASK_OPCODE = 0xF0;
  private static final int BIND_MASK_IMMEDIATE = 0x0F;

  private static final int BIND_OPCODE_DONE = 0x00;
  private static final int BIND_OPCODE_SET_DYLIB_ORDINAL_IMM = 0x10;
  private static final int BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB = 0x20;
  private static final int BIND_OPCODE_SET_DYLIB_SPECIAL_IMM = 0x30;
  private static final int BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM = 0x40;
  private static final int BIND_OPCODE_SET_TYPE_IMM = 0x50;
  private static final int BIND_OPCODE_SET_ADDEND_SLEB = 0x60;
  private static final int BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB = 0x70;
  private static final int BIND_OPCODE_ADD_ADDR_ULEB = 0x80;
  private static final int BIND_OPCODE_DO_BIND = 0x90;
  private static final int BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB = 0xA0;
  private static final int BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED = 0xB0;
  private static final int BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB = 0xC0;
  private static final int BIND_OPCODE_THREADED = 0xD0;

  private static final int BIND_SPECIAL_DYLIB_SELF = 0;
  private static final int BIND_SPECIAL_DYLIB_MAIN_EXECUTABLE = -1;
  private static final int BIND_SPECIAL_DYLIB_FLAT_LOOKUP = -2;

  private MachoBindInfoReader() {}

  /**
   * Parses the binding information from a Mach-O executable and returns the set of symbols to be
   * bound at runtime. Note that a symbol might have multiple bind points but we're only returning a
   * set of the symbols, as we're not interested in the binding addresses.
   *
   * <p>If there's no binding info in the executable, an empty optional is returned.
   *
   * <p>Mach-O executables have three different sets of binding info: strong, weak and lazy. The
   * format is the same for all three, so those could be exposed in the future if needed.
   *
   * <p>This method returns the strong and lazy bound symbols.
   */
  public static ImmutableSet<MachoBindInfoSymbol> parseStrongAndLazyBoundSymbols(
      ByteBuffer machoExecutable) throws Machos.MachoException {

    ImmutableList<String> dependentLibs =
        MachoDependentLibraryReader.getDependentLibraries(machoExecutable);

    Optional<MachoDyldInfoCommand> maybeDyldInfoCommand =
        MachoDyldInfoCommandReader.read(machoExecutable);

    if (maybeDyldInfoCommand.isPresent()) {
      MachoDyldInfoCommand command = maybeDyldInfoCommand.get();
      ImmutableSet.Builder<MachoBindInfoSymbol> symbolBuilder = ImmutableSet.builder();
      parseBindInfoAtOffset(
          machoExecutable,
          command.getBindInfoOffset(),
          command.getBindInfoSize(),
          true,
          dependentLibs,
          symbolBuilder);
      parseBindInfoAtOffset(
          machoExecutable,
          command.getLazyBindInfoOffset(),
          command.getLazyBindInfoSize(),
          // Linker inserts BIND_OPCODE_DONE after each bind
          false,
          dependentLibs,
          symbolBuilder);
      return symbolBuilder.build();
    }

    return ImmutableSet.of();
  }

  /**
   * For reference around Mach-O format, see ImageLoaderMachOCompressed::eachBind() in
   * ImageLoaderMachOCompressed.cpp (from Apple's dyld open source releases).
   */
  private static void parseBindInfoAtOffset(
      ByteBuffer machoExecutable,
      int offset,
      int size,
      boolean stopOnDone,
      ImmutableList<String> dependentLibraries,
      ImmutableSet.Builder<MachoBindInfoSymbol> symbolBuilder)
      throws Machos.MachoException {
    if (offset == 0 || size == 0) {
      // No binding info present in executable, this is a valid state.
      return;
    }

    MachoBindContext bindContext = new MachoBindContext();
    machoExecutable.position(offset);

    boolean done = false;
    while (!done && machoExecutable.position() < (offset + size)) {
      byte data = machoExecutable.get();
      int opcode = (data & BIND_MASK_OPCODE);
      int immediate = (data & BIND_MASK_IMMEDIATE);

      switch (opcode) {
        case BIND_OPCODE_DONE:
          done = stopOnDone;
          break;

        case BIND_OPCODE_SET_DYLIB_ORDINAL_IMM:
          bindContext.libraryOrdinal = Optional.of(immediate);
          break;
        case BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB:
          bindContext.libraryOrdinal = Optional.of((int) ULEB128.read(machoExecutable));
          break;
        case BIND_OPCODE_SET_DYLIB_SPECIAL_IMM:
          // NB: (byte) typecasts needed to produce correct negative values
          bindContext.libraryOrdinal =
              Optional.of(
                  (immediate == 0 ? immediate : ((byte) BIND_MASK_OPCODE | (byte) immediate)));
          break;

        case BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM:
          String string = ObjectFileScrubbers.decodeCString(machoExecutable);
          bindContext.symbolName = Optional.of(string);
          break;

        case BIND_OPCODE_SET_TYPE_IMM:
          // We're not tracking type flags, so no work to be done.
          break;

        case BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB:
        case BIND_OPCODE_ADD_ADDR_ULEB:
        case BIND_OPCODE_SET_ADDEND_SLEB:
          // SLEB and ULEB use the same terminating condition, so we can just interpret the sequence
          // as an ULEB, since we do not actually use the return value.
          ULEB128.read(machoExecutable);
          break;

        case BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED: // Do not care about the address offset
        case BIND_OPCODE_DO_BIND:
          processBind(bindContext, dependentLibraries, symbolBuilder);
          break;
        case BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB:
          processBind(bindContext, dependentLibraries, symbolBuilder);
          // Do not care about the address offset but still need to advance the buffer position.
          ULEB128.read(machoExecutable);
          break;
        case BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB:
          // This command binds the same symbol multiple times but since we're only interested
          // in the set of symbols, there's no need for a loop.
          processBind(bindContext, dependentLibraries, symbolBuilder);
          // Do not care about addresses but still need to advance the buffer position.
          ULEB128.read(machoExecutable);
          ULEB128.read(machoExecutable);
          break;

        case BIND_OPCODE_THREADED:
          // TODO(mgd): Add support for ARM64e threaded binding
          throw new Machos.MachoException("ARM64e threaded binding is currently unsupported");
        default:
          throw new Machos.MachoException(
              String.format("Encountered unknown opcode: %d, immediate: %d", opcode, immediate));
      }
    }
  }

  /**
   * Processes a symbol binding by computing the symbol to be bound and adds it to the given {@code
   * symbolBuilder}.
   */
  private static void processBind(
      MachoBindContext bindContext,
      ImmutableList<String> dependentLibraries,
      ImmutableSet.Builder<MachoBindInfoSymbol> symbolBuilder)
      throws Machos.MachoException {
    if (!bindContext.libraryOrdinal.isPresent()) {
      throw new Machos.MachoException("Trying to bind symbol before a library ordinal being set");
    }

    if (!bindContext.symbolName.isPresent()) {
      throw new Machos.MachoException("Trying to bind symbol without a symbol name");
    }

    MachoBindInfoSymbol.LibraryLookup libraryLookup;
    Optional<String> library;

    int libraryOrdinal = bindContext.libraryOrdinal.get();
    if (libraryOrdinal > 0) {
      // Library ordinals use 1-based indexing
      int libraryIndex = libraryOrdinal - 1;
      if (libraryIndex >= dependentLibraries.size()) {
        throw new Machos.MachoException(
            String.format(
                "Invalid library ordinal %d, exceeds size of dependent libraries %d",
                bindContext.libraryOrdinal, dependentLibraries.size()));
      }

      library = Optional.of(dependentLibraries.get(libraryIndex));
      libraryLookup = MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY;
    } else {
      library = Optional.empty();
      switch (libraryOrdinal) {
        case BIND_SPECIAL_DYLIB_SELF:
          libraryLookup = MachoBindInfoSymbol.LibraryLookup.SELF;
          break;
        case BIND_SPECIAL_DYLIB_MAIN_EXECUTABLE:
          libraryLookup = MachoBindInfoSymbol.LibraryLookup.MAIN_EXECUTABLE;
          break;
        case BIND_SPECIAL_DYLIB_FLAT_LOOKUP:
          libraryLookup = MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP;
          break;
        default:
          throw new Machos.MachoException(
              String.format(
                  "Encountered unknown special library ordinal %d", bindContext.libraryOrdinal));
      }
    }

    symbolBuilder.add(MachoBindInfoSymbol.of(bindContext.symbolName.get(), library, libraryLookup));
  }
}
