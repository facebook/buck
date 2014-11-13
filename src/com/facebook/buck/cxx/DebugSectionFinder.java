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

package com.facebook.buck.cxx;

import static com.facebook.buck.cxx.DebugSectionProperty.COMPRESSED;
import static com.facebook.buck.cxx.DebugSectionProperty.STRINGS;

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfSection;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.ByteBuffer;

public class DebugSectionFinder {

  // The ELF sections names which correspond to debug sections mapped to their properties.
  private static final ImmutableMap<String, ImmutableSet<DebugSectionProperty>> ELF_DEBUG_SECTIONS =
      ImmutableMap.<String, ImmutableSet<DebugSectionProperty>>builder()
          // DWARF sections
          .put(".debug_str", ImmutableSet.of(STRINGS))
          .put(".debug_line", ImmutableSet.of(STRINGS))
          .put(".zdebug_str", ImmutableSet.of(STRINGS, COMPRESSED))
          .put(".zdebug_line", ImmutableSet.of(STRINGS, COMPRESSED))
          // STABS sections
          .put(".stabstr", ImmutableSet.of(STRINGS))
          .build();

  // Locate, if any, the debug sections in the ELF file represented by the given buffer.
  private ImmutableMap<String, DebugSection> findElf(ByteBuffer buffer) {
    ImmutableMap.Builder<String, DebugSection> debugSectionsBuilder = ImmutableMap.builder();
    Elf elf = new Elf(buffer);
    for (int i = 0; i < elf.getNumberOfSections(); i++) {
      ElfSection section = elf.getSectionByIndex(i);
      String name = elf.getSectionName(section.header);
      ImmutableSet<DebugSectionProperty> properties = ELF_DEBUG_SECTIONS.get(name);
      if (properties != null) {
        buffer.position((int) section.header.sh_off);
        ByteBuffer body = buffer.slice();
        body.limit((int) section.header.sh_size);
        debugSectionsBuilder.put(name, new DebugSection(properties, body));
      }
    }
    return debugSectionsBuilder.build();
  }

  /**
   * @return a map of all the debug sections found in executable format represented as
   *     {@code buffer}, or {@link Optional#absent()} if the format was not recognized.
   */
  public Optional<ImmutableMap<String, DebugSection>> find(ByteBuffer buffer) {
    if (Elf.isElf(buffer)) {
      return Optional.of(findElf(buffer));
    } else {
      return Optional.absent();
    }
  }

}
