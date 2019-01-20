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
import com.facebook.buck.cxx.toolchain.elf.ElfHeader;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSectionLookupResult;
import com.facebook.buck.cxx.toolchain.elf.ElfSymbolTable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.immutables.value.Value;

/** A step which scrubs an ELF symbol table of information relevant to dynamic linking. */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfSymbolTableScrubberStep implements Step {

  @VisibleForTesting static final int STABLE_SECTION = 1;

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  abstract String getSection();

  abstract Optional<String> getVersymSection();

  abstract boolean isAllowMissing();

  abstract boolean isScrubUndefinedSymbols();

  private ElfSymbolTable fixUpSymbolTable(ElfSymbolTable table) {
    ImmutableList.Builder<ElfSymbolTable.Entry> entries = ImmutableList.builder();

    // The first symbol serves as the undefined symbol index, so always include it and start
    // processing symbols after it.
    entries.add(table.entries.get(0));

    // Fixup and add the remaining entries.
    AtomicInteger count = new AtomicInteger();
    RichStream.from(table.entries)
        .skip(1)
        // Filter out undefined symbols.
        .filter(entry -> !isScrubUndefinedSymbols() || entry.st_shndx != 0)
        // Generate a new sanitized symbol table entry.
        .map(
            entry ->
                new ElfSymbolTable.Entry(
                    entry.st_name,
                    entry.st_info,
                    entry.st_other,
                    // A section index of 0 is special and means the symbol is undefined, so we
                    // must maintain that.  Otherwise, if it's non-zero, fix it up to an arbitrary
                    // stable section value so the number and ordering of sections can never affect
                    // the content of the symbol table.
                    entry.st_shndx > 0 ? STABLE_SECTION : entry.st_shndx,
                    // Substitute non-zero addresses, dependent on size/layout of sections with a
                    // stable address determined by the index of this symbol table entry in the
                    // symbol table.
                    entry.st_value == 0 ? 0 : count.incrementAndGet(),
                    // For functions, set the size to zero.
                    entry.st_info.st_type == ElfSymbolTable.Entry.Info.Type.STT_FUNC
                        ? 0
                        : entry.st_size))
        .forEach(entries::add);

    return new ElfSymbolTable(entries.build());
  }

  // Read in versions from the ELF file.
  private ImmutableList<Integer> parseVersions(ElfHeader.EIClass eiClass, ElfSection verSym) {
    ImmutableList.Builder<Integer> versions = ImmutableList.builder();
    while (verSym.body.hasRemaining()) {
      int val;
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        val = Elf.Elf32.getElf32Half(verSym.body);
      } else {
        val = Elf.Elf64.getElf64Half(verSym.body);
      }
      versions.add(val);
    }
    return versions.build();
  }

  // Write out versions to the ELF file.
  private void writeVersions(
      ElfHeader.EIClass eiClass, ByteBuffer buffer, Iterable<Integer> versions) {
    for (int version : versions) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Half(buffer, (short) version);
      } else {
        Elf.Elf64.putElf64Half(buffer, (short) version);
      }
    }
  }

  // Drop versions whose corresponding symbol is undefined, and therefore getting scrubbed from the
  // symbol table.
  private ImmutableMap<Integer, Integer> fixUpVersions(
      ElfSymbolTable table, ImmutableList<Integer> versions) {
    Preconditions.checkArgument(table.entries.size() == versions.size());
    Preconditions.checkState(isScrubUndefinedSymbols());
    ImmutableMap.Builder<Integer, Integer> fixedVersions = ImmutableMap.builder();
    fixedVersions.put(0, versions.get(0));
    for (int index = 1; index < table.entries.size(); index++) {
      if (table.entries.get(index).st_shndx != 0) {
        fixedVersions.put(index, versions.get(index));
      }
    }
    return fixedVersions.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            getFilesystem().resolve(getPath()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      Elf elf = new Elf(buffer);

      // Locate the symbol table section.
      Optional<ElfSectionLookupResult> sectionResult = elf.getSectionByName(getSection());
      if (!sectionResult.isPresent()) {
        if (isAllowMissing()) {
          return StepExecutionResults.SUCCESS;
        } else {
          throw new IOException(
              String.format(
                  "Error parsing ELF file %s: no such section \"%s\"", getPath(), getSection()));
        }
      }

      int sectionIndex = sectionResult.get().getIndex();
      ElfSection section = sectionResult.get().getSection();

      // Read in and fixup the symbol table then write it back out.
      ElfSymbolTable table = ElfSymbolTable.parse(elf.header.ei_class, section.body);
      ElfSymbolTable fixedUpTable = fixUpSymbolTable(table);
      section.body.rewind();
      fixedUpTable.write(elf.header.ei_class, section.body);

      // If we've dropped some symbols, we have some additional work to do.
      if (table.entries.size() != fixedUpTable.entries.size()) {
        Preconditions.checkState(isScrubUndefinedSymbols());

        // Fixup the section header with the new size and write it out.
        buffer.position((int) (elf.header.e_shoff + sectionIndex * elf.header.e_shentsize));
        section.header.withSize(section.body.position()).write(elf.header.ei_class, buffer);

        // If a versym section is given, also update it to remove dropped symbols.
        if (getVersymSection().isPresent()) {
          Optional<ElfSectionLookupResult> versymSectionResult =
              elf.getSectionByName(getVersymSection().get());
          if (versymSectionResult.isPresent()) {
            int versymSectionIndex = versymSectionResult.get().getIndex();
            ElfSection versymSection = versymSectionResult.get().getSection();

            // Remove dropped symbols from the version symbol table and re-write it.
            ImmutableList<Integer> versions = parseVersions(elf.header.ei_class, versymSection);
            ImmutableMap<Integer, Integer> fixedVersions = fixUpVersions(table, versions);
            versymSection.body.rewind();
            writeVersions(elf.header.ei_class, versymSection.body, fixedVersions.values());

            // Fixup the version section header with the new size and write it out.
            buffer.position(
                (int) (elf.header.e_shoff + versymSectionIndex * elf.header.e_shentsize));
            versymSection
                .header
                .withSize(versymSection.body.position())
                .write(elf.header.ei_class, buffer);
          }
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
    return String.format("Scrub ELF symbol table %s in %s", getSection(), getPath());
  }
}
