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

import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSectionLookupResult;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public class ElfFile {
  /** Load the given ELF file into memory. */
  public static Elf mapReadOnly(Path elfFilePath) throws IOException {
    try (FileChannel channel = FileChannel.open(elfFilePath, StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      return new Elf(buffer);
    }
  }

  /** @return the value of the ELF file's DT_SONAME .dynamic entry, if present. */
  public static Optional<String> getSoname(Path elfFilePath) throws IOException {
    return getSoname(mapReadOnly(elfFilePath));
  }

  /** @return the value of the ELF file's DT_SONAME .dynamic entry, if present. */
  public static Optional<String> getSoname(Elf elf) {
    Optional<ElfSectionLookupResult> dynamicSectionLookupResult = elf.getSectionByName(".dynamic");
    if (!dynamicSectionLookupResult.isPresent()) {
      return Optional.empty();
    }
    ElfSection dynamicSection = dynamicSectionLookupResult.get().getSection();
    ElfDynamicSection dynamic = ElfDynamicSection.parse(elf.header.ei_class, dynamicSection.body);
    Optional<Long> sonameOffset = dynamic.lookup(ElfDynamicSection.DTag.DT_SONAME);
    if (!sonameOffset.isPresent()) {
      return Optional.empty();
    }
    ElfSection strtabSection = elf.getSectionByIndex((int) dynamicSection.header.sh_link);
    String soname = strtabSection.lookupString(sonameOffset.get());
    return Optional.of(soname);
  }
}
