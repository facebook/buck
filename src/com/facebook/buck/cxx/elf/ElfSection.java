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

import java.nio.ByteBuffer;

/**
 * Encapsulates the header information and raw body of an ELF section.
 */
// CHECKSTYLE.OFF: LocalVariableName
// CHECKSTYLE.OFF: ParameterName
public class ElfSection {

  public final ElfSectionHeader header;
  public final ByteBuffer body;

  ElfSection(ElfSectionHeader header, ByteBuffer body) {
    this.header = header;
    this.body = body;
  }

  static ElfSection parse(ElfHeader.EIClass ei_class, ByteBuffer buffer) {
    ElfSectionHeader header = ElfSectionHeader.parse(ei_class, buffer);

    // If section is of type SHT_NULL or SHT_NOBITS, it has no body in the file.
    // Otherwise, use the offset and size to bound the body of the section from the input buffer.
    ByteBuffer body;
    if (header.sh_type == ElfSectionHeader.SHType.SHT_NULL ||
        header.sh_type == ElfSectionHeader.SHType.SHT_NOBITS) {
      body = ByteBuffer.wrap(new byte[0]);
    } else {
      buffer.position((int) header.sh_off);
      body = buffer.slice();
      body.limit((int) header.sh_size);
    }

    return new ElfSection(header, body);

  }

  /**
   * @return the {@link String} found in this section, interpreted as a string table, at the
   *     given offset.
   */
  public String lookupString(long offset) {
    body.position((int) offset);
    StringBuilder builder = new StringBuilder();
    char c;
    while ((c = (char) body.get()) != '\0') {
      builder.append(c);
    }
    return builder.toString();
  }

}

// CHECKSTYLE.ON: ParameterName
// CHECKSTYLE.ON: LocalVariableName
