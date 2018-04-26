/*
 * Copyright 2016-present Facebook, Inc.
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

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSectionLookupResult;
import com.facebook.buck.cxx.toolchain.elf.ElfSymbolTable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A step which scrubs all information from the ".dynamic" section of an ELF file which is
 * irrelevant at link time.
 */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfDynamicSectionScrubberStep implements Step {

  static final String SECTION = ".dynamic";

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  /** @return the dynamic tags to whitelist from scrubbing. */
  abstract ImmutableSet<ElfDynamicSection.DTag> getWhitelistedTags();

  /** @return whether to remove scrubbed tags by rewriting the dynamic section. */
  abstract boolean isRemoveScrubbedTags();

  private boolean isKeepTag(ElfDynamicSection.DTag tag) {
    // Keep whitelisted tags and `DT_NULL` (as it marks the end of the dynamic array).
    return getWhitelistedTags().contains(tag) || tag == ElfDynamicSection.DTag.DT_NULL;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    try (FileChannel channel =
        FileChannel.open(
            getFilesystem().resolve(getPath()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfSectionLookupResult sectionResult = elf.getMandatorySectionByName(getPath(), SECTION);
      int sectionIndex = sectionResult.getIndex();
      ElfSection section = sectionResult.getSection();

      // Parse the dynamic section.
      ElfDynamicSection dynamic = ElfDynamicSection.parse(elf.header.ei_class, section.body);

      // Generate a new dynamic section with only the whitelisted tags.
      ElfDynamicSection newDynamic =
          new ElfDynamicSection(
              RichStream.from(dynamic.entries)
                  .filter(e -> isKeepTag(e.d_tag) || !isRemoveScrubbedTags())
                  .map(e -> isKeepTag(e.d_tag) ? e : new ElfDynamicSection.Entry(e.d_tag, 0L))
                  .toImmutableList());

      // Write out the new dynamic symbol table.
      section.body.rewind();
      newDynamic.write(elf.header.ei_class, section.body);

      // Update the size in other parts of the ELF file, if necessary.
      if (dynamic.entries.size() != newDynamic.entries.size()) {
        Preconditions.checkState(isRemoveScrubbedTags());

        // Update the section header.
        buffer.position((int) (elf.header.e_shoff + sectionIndex * elf.header.e_shentsize));
        section.header.withSize(section.body.position()).write(elf.header.ei_class, buffer);

        // Update the `_DYNAMIC` symbol in the symbol table.
        Optional<ElfSectionLookupResult> symtabSection = elf.getSectionByName(".symtab");
        if (symtabSection.isPresent()) {
          ElfSymbolTable symtab =
              ElfSymbolTable.parse(elf.header.ei_class, symtabSection.get().getSection().body);
          ElfSection strtab = elf.getMandatorySectionByName(getPath(), ".strtab").getSection();
          ElfSymbolTable newSymtab =
              new ElfSymbolTable(
                  RichStream.from(symtab.entries)
                      .map(
                          entry ->
                              strtab.lookupString(entry.st_name).equals("_DYNAMIC")
                                  ? entry.withSize(section.body.position())
                                  : entry)
                      .toImmutableList());

          // Write out the new symbol table.
          symtabSection.get().getSection().body.rewind();
          newSymtab.write(elf.header.ei_class, symtabSection.get().getSection().body);
        }
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public final String getShortName() {
    return "scrub_symbol_table";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Scrub ELF symbol table in " + getPath();
  }
}
