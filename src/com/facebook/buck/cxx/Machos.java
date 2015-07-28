/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.util.MoreStrings;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Machos {

  // http://www.opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/loader.h
  // File magic
  static final byte[] MH_MAGIC = Ints.toByteArray(0xFEEDFACE);
  static final byte[] MH_MAGIC_64 = Ints.toByteArray(0xFEEDFACF);
  static final byte[] MH_CIGAM = Ints.toByteArray(0xCEFAEDFE);
  static final byte[] MH_CIGAM_64 = Ints.toByteArray(0xCFFAEDFE);
  // Map segment load command
  static final int LC_SEGMENT = 0x1;
  // Symbol table load command
  static final int LC_SYMTAB = 0x2;
  // UUID load command
  static final int LC_UUID = 0x1B;
  // Map 64 bit segment load command
  static final int LC_SEGMENT_64 = 0x19;

  // http://www.opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/stab.h
  // Description of object file STAB entries
  static final short N_OSO = (short) 0x66;

  private Machos() {}

  static void setUuid(MappedByteBuffer map, byte[] uuid) throws MachoException {
    int commandsCount = getHeader(map).getCommandsCount();

    for (int i = 0; i < commandsCount; i++) {
      int command = ObjectFileScrubbers.getLittleEndianInt(map);
      int commandSize = ObjectFileScrubbers.getLittleEndianInt(map);
      if (LC_UUID == command) {
        ObjectFileScrubbers.putBytes(map, uuid);
        return;
      } else {
        /* Command body */ ObjectFileScrubbers.getBytes(map, commandSize - 8);
      }
    }

    throw new MachoException("LC_UUID command not found");
  }

  static void relativizeOsoSymbols(FileChannel file, Path linkingDirectory)
      throws IOException, MachoException {
    Preconditions.checkState(linkingDirectory.isAbsolute());

    long size = file.size();
    MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, size);

    MachoHeader header = getHeader(map);

    int symbolTableOffset = 0;
    int symbolTableCount = 0;
    int stringTableOffset = 0;
    int stringTableSizePosition = 0;
    int stringTableSize = 0;
    boolean symbolTableSegmentFound = false;
    int segmentSizePosition = 0;
    int segmentSize = 0;

    int commandsCount = header.getCommandsCount();
    for (int i = 0; i < commandsCount; i++) {
      int commandStart = map.position(); // NOPMD
      int command = ObjectFileScrubbers.getLittleEndianInt(map);
      int commandSize = ObjectFileScrubbers.getLittleEndianInt(map); // NOPMD
      switch (command) {
        case LC_SYMTAB:
          symbolTableOffset = ObjectFileScrubbers.getLittleEndianInt(map);
          symbolTableCount = ObjectFileScrubbers.getLittleEndianInt(map);
          stringTableOffset = ObjectFileScrubbers.getLittleEndianInt(map);
          stringTableSizePosition = map.position();
          stringTableSize = ObjectFileScrubbers.getLittleEndianInt(map);
          symbolTableSegmentFound = true;
          break;
        case LC_SEGMENT:
          /* segment name */ ObjectFileScrubbers.getBytes(map, 16);
          /* vm address */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* vm size */ ObjectFileScrubbers.getLittleEndianInt(map);
          int segmentFileOffset = ObjectFileScrubbers.getLittleEndianInt(map);
          int segmentFileSizePosition = map.position();
          int segmentFileSize = ObjectFileScrubbers.getLittleEndianInt(map);
          /* maximum vm protection */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* initial vm protection */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* number of sections */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* flags */ ObjectFileScrubbers.getLittleEndianInt(map);

          if (segmentFileOffset + segmentFileSize == size) {
            if (segmentSizePosition != 0) {
              throw new MachoException("multiple map segment commands map string table");
            }
            segmentSizePosition = segmentFileSizePosition;
            segmentSize = segmentFileSize;
          }
          break;
        case LC_SEGMENT_64:
          /* segment name */ ObjectFileScrubbers.getBytes(map, 16);
          /* vm address */ ObjectFileScrubbers.getLittleEndianLong(map);
          /* vm size */ ObjectFileScrubbers.getLittleEndianLong(map);
          long segment64FileOffset = ObjectFileScrubbers.getLittleEndianLong(map);
          int segment64FileSizePosition = map.position();
          long segment64FileSize = ObjectFileScrubbers.getLittleEndianLong(map);
          /* maximum vm protection */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* initial vm protection */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* number of sections */ ObjectFileScrubbers.getLittleEndianInt(map);
          /* flags */ ObjectFileScrubbers.getLittleEndianInt(map);

          if (segment64FileOffset + segment64FileSize == size) {
            if (segmentSizePosition != 0) {
              throw new MachoException("multiple map segment commands map string table");
            }
            segmentSizePosition = segment64FileSizePosition;
            if (segment64FileSize > Ints.MAX_POWER_OF_TWO) {
              throw new MachoException("map segment file size too big");
            }
            segmentSize = (int) segment64FileSize;
          }
          break;
      }
      map.position(commandStart + commandSize);
    }

    if (!symbolTableSegmentFound) {
      throw new MachoException("LC_SYMTAB command not found");
    }
    if (stringTableOffset + stringTableSize != size) {
      throw new MachoException("String table does not end at end of file");
    }
    if (stringTableSize == 0) {
      return;
    }
    if (segmentSizePosition == 0 || segmentSize == 0) {
      throw new MachoException("LC_SEGMENT or LC_SEGMENT_64 command for string table not found");
    }

    map.position(stringTableOffset);
    if (map.get() != 0x20) {
      throw new MachoException("First character in the string table is not a space");
    }
    if (map.get() != 0x00) {
      throw new MachoException("Second character in the string table is not a NUL");
    }
    int currentStringTableOffset = map.position();

    byte[] stringTableBytes = new byte[stringTableSize];
    map.position(stringTableOffset);
    map.get(stringTableBytes);
    ByteBuffer stringTable = ByteBuffer.wrap(stringTableBytes);

    map.position(symbolTableOffset);

    Map<Integer, Integer> strings = new HashMap<>();
    String prefix = linkingDirectory.toString() + "/";
    for (int i = 0; i < symbolTableCount; i++) {
      int stringTableIndexPosition = map.position();
      int stringTableIndex = ObjectFileScrubbers.getLittleEndianInt(map);
      byte type = map.get();
      /* section */ map.get();
      /* description */ ObjectFileScrubbers.getLittleEndianShort(map);
      int valuePosition = map.position();
      if (header.getIs64Bit()) {
        /* value */ ObjectFileScrubbers.getLittleEndianLong(map);
      } else {
        /* value */ ObjectFileScrubbers.getLittleEndianInt(map);
      }
      if (stringTableIndex < 2) {
        continue;
      }

      int position = map.position();
      try {
        int newStringTableIndex;
        if (strings.containsKey(stringTableIndex)) {
          newStringTableIndex = strings.get(stringTableIndex);
        } else {
          stringTable.position(stringTableIndex);
          String string = ObjectFileScrubbers.getAsciiString(stringTable);
          if (type == N_OSO) {
            string = MoreStrings.stripPrefix(string, prefix).or(string);
            map.position(valuePosition);
            if (header.getIs64Bit()) {
              ObjectFileScrubbers.putLittleEndianLong(map, Ints.MAX_POWER_OF_TWO);
            } else {
              ObjectFileScrubbers.putLittleEndianInt(map, Ints.MAX_POWER_OF_TWO);
            }
          }
          map.position(currentStringTableOffset);
          ObjectFileScrubbers.putAsciiString(map, string);
          newStringTableIndex = currentStringTableOffset - stringTableOffset;
          currentStringTableOffset = map.position();
          strings.put(stringTableIndex, newStringTableIndex);
        }
        map.position(stringTableIndexPosition);
        ObjectFileScrubbers.putLittleEndianInt(map, newStringTableIndex);
      } finally {
        map.position(position);
      }
    }

    map.position(stringTableSizePosition);
    int newStringTableSize = currentStringTableOffset - stringTableOffset;
    ObjectFileScrubbers.putLittleEndianInt(map, newStringTableSize);

    map.position(segmentSizePosition);
    ObjectFileScrubbers.putLittleEndianInt(
        map,
        segmentSize + (newStringTableSize - stringTableSize));

    file.truncate(currentStringTableOffset);
  }

  private static MachoHeader getHeader(MappedByteBuffer map) throws MachoException {
    byte[] magic = ObjectFileScrubbers.getBytes(map, MH_MAGIC.length);
    boolean is64bit;
    if (Arrays.equals(MH_MAGIC, magic) || Arrays.equals(MH_CIGAM, magic)) {
      is64bit = false;
    } else if (Arrays.equals(MH_MAGIC_64, magic) || Arrays.equals(MH_CIGAM_64, magic)) {
      is64bit = true;
    } else {
      throw new MachoException("invalid Mach-O magic");
    }

    /* CPU type */
    ObjectFileScrubbers.getLittleEndianInt(map);
    /* CPU subtype */
    ObjectFileScrubbers.getLittleEndianInt(map);
    /* File type */
    ObjectFileScrubbers.getLittleEndianInt(map);
    int commandsCount = ObjectFileScrubbers.getLittleEndianInt(map);
    /* Commands size */
    ObjectFileScrubbers.getLittleEndianInt(map);
    /* Flags */
    ObjectFileScrubbers.getLittleEndianInt(map);
    if (is64bit) {
      /* reserved */ ObjectFileScrubbers.getLittleEndianInt(map);
    }
    return MachoHeader.of(commandsCount, is64bit);
  }

  @SuppressWarnings("serial")
  public static class MachoException extends Exception {
    public MachoException(String msg) {
      super(msg);
    }
  }

}
