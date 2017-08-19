/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.elf;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

public class Elf {

  // The underlying buffer which contains the ELF file data.
  private final ByteBuffer buffer;

  public final ElfHeader header;

  // A cache of parsed section headers.
  private final List<ElfSection> sections;

  public Elf(ByteBuffer buffer) {
    this.buffer = Preconditions.checkNotNull(buffer);

    // Eagerly parse the header, since we'll need this info for most/all operations.
    this.header = ElfHeader.parse(this.buffer);

    // Initialize a cache we'll use to store parsed section headers.
    this.sections = new ArrayList<>(Collections.<ElfSection>nCopies(header.e_shnum, null));
  }

  public int getNumberOfSections() {
    return header.e_shnum;
  }

  /** @return the parsed section header for the section at the given index. */
  public ElfSection getSectionByIndex(int index) {
    Preconditions.checkArgument(index >= 0 && index < header.e_shnum);
    ElfSection section = sections.get(index);
    if (section == null) {
      buffer.position((int) (header.e_shoff + index * header.e_shentsize));
      section = ElfSection.parse(header.ei_class, buffer);
      sections.set(index, section);
    }
    return section;
  }

  /** @return the name of the section found in the section header string table. */
  public String getSectionName(ElfSectionHeader sectionHeader) {
    ElfSection stringTable = getSectionByIndex(header.e_shstrndx);
    return stringTable.lookupString(sectionHeader.sh_name);
  }

  /** @return the parsed section header for the section of the given name. */
  public Optional<ElfSectionLookupResult> getSectionByName(String name) {
    ElfSection stringTable = getSectionByIndex(header.e_shstrndx);
    for (int index = 0; index < header.e_shnum; index++) {
      ElfSection section = getSectionByIndex(index);
      String sectionName = stringTable.lookupString(section.header.sh_name);
      if (name.equals(sectionName)) {
        return Optional.of(ElfSectionLookupResult.of(index, section));
      }
    }
    return Optional.empty();
  }

  public ElfSectionLookupResult getMandatorySectionByName(Object fileName, String sectionName)
      throws IOException {
    Optional<ElfSectionLookupResult> result = getSectionByName(sectionName);
    if (!result.isPresent()) {
      throw new IOException(
          String.format(
              "Error parsing ELF file %s: no such section \"%s\"", fileName, sectionName));
    }
    return result.get();
  }

  /** @return whether the data this buffer points to is most likely ELF. */
  public static boolean isElf(ByteBuffer buffer) {
    byte[] magic = new byte[4];
    if (buffer.remaining() < magic.length) {
      return false;
    }
    buffer.slice().get(magic);
    return (magic[ElfHeader.EI_MAG0] == ElfHeader.ELFMAG0
        && magic[ElfHeader.EI_MAG1] == ElfHeader.ELFMAG1
        && magic[ElfHeader.EI_MAG2] == ElfHeader.ELFMAG2
        && magic[ElfHeader.EI_MAG3] == ElfHeader.ELFMAG3);
  }

  public static class Elf32 {

    private Elf32() {}

    public static long getElf32Addr(ByteBuffer buffer) {
      return buffer.getInt() & 0xffffffffL;
    }

    public static void putElf32Addr(ByteBuffer buffer, int val) {
      buffer.putInt(val);
    }

    public static long getElf32Word(ByteBuffer buffer) {
      return buffer.getInt() & 0xffffffffL;
    }

    public static void putElf32Word(ByteBuffer buffer, int val) {
      buffer.putInt(val);
    }

    public static int getElf32Half(ByteBuffer buffer) {
      return buffer.getShort() & 0xffff;
    }

    public static void putElf32Half(ByteBuffer buffer, short val) {
      buffer.putShort(val);
    }

    public static int getElf32Sword(ByteBuffer buffer) {
      return buffer.getInt();
    }

    public static void putElf32Sword(ByteBuffer buffer, int val) {
      buffer.putInt(val);
    }
  }

  public static class Elf64 {

    private Elf64() {}

    public static long getElf64Addr(ByteBuffer buffer) {
      return buffer.getLong();
    }

    public static void putElf64Addr(ByteBuffer buffer, long val) {
      buffer.putLong(val);
    }

    public static long getElf64Word(ByteBuffer buffer) {
      return (buffer.getInt() & 0xffffffffL);
    }

    public static void putElf64Word(ByteBuffer buffer, int val) {
      buffer.putInt(val);
    }

    public static long getElf64Xword(ByteBuffer buffer) {
      return buffer.getLong();
    }

    public static void putElf64Xword(ByteBuffer buffer, long val) {
      buffer.putLong(val);
    }

    public static int getElf64Half(ByteBuffer buffer) {
      return buffer.getShort() & 0xffff;
    }

    public static void putElf64Half(ByteBuffer buffer, short val) {
      buffer.putShort(val);
    }

    public static long getElf64Sxword(ByteBuffer buffer) {
      return buffer.getLong();
    }

    public static void putElf64Sxword(ByteBuffer buffer, long val) {
      buffer.putLong(val);
    }
  }

  /**
   * A tuple of section index and {@link ElfSection} object returned from lookup functions in this
   * class.
   */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractElfSectionLookupResult {

    /** @return the index of the section in the ELF file. */
    int getIndex();

    /** @return the parsed {@link ElfSection}. */
    ElfSection getSection();
  }
}
