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

package com.facebook.buck.cxx.elf;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

  /**
   * @return the parsed section header for the section at the given index.
   */
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

  /**
   * @return the name of the section found in the section header string table.
   */
  public String getSectionName(ElfSectionHeader sectionHeader) {
    ElfSection stringTable = getSectionByIndex(header.e_shstrndx);
    return stringTable.lookupString(sectionHeader.sh_name);
  }

  /**
   * @return the parsed section header for the section of the given name.
   */
  public Optional<ElfSection> getSectionByName(String name) {
    ElfSection stringTable = getSectionByIndex(header.e_shstrndx);
    for (int i = 0; i < header.e_shnum; i++) {
      ElfSection section = getSectionByIndex(i);
      String sectionName = stringTable.lookupString(section.header.sh_name);
      if (name.equals(sectionName)) {
        return Optional.of(section);
      }
    }
    return Optional.absent();
  }

  /**
   * @return whether the data this buffer points to is most likely ELF.
   */
  public static boolean isElf(ByteBuffer buffer) {
    byte[] magic = new byte[4];
    buffer.slice().get(magic);
    return (
        magic[ElfHeader.EI_MAG0] == ElfHeader.ELFMAG0 &&
        magic[ElfHeader.EI_MAG1] == ElfHeader.ELFMAG1 &&
        magic[ElfHeader.EI_MAG2] == ElfHeader.ELFMAG2 &&
        magic[ElfHeader.EI_MAG3] == ElfHeader.ELFMAG3);
  }

}
