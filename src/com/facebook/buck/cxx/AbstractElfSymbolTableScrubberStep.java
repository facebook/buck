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

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.cxx.elf.ElfSymbolTable;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.immutables.value.Value;

/** A step which scrubs an ELF symbol table of information relevant to dynamic linking. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractElfSymbolTableScrubberStep implements Step {

  @VisibleForTesting static final int STABLE_SECTION = 1;

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  abstract String getSection();

  abstract boolean isAllowMissing();

  private ElfSymbolTable fixUpSymbolTable(ElfSymbolTable table) {
    ImmutableList.Builder<ElfSymbolTable.Entry> entries = ImmutableList.builder();

    // The first symbol serves as the undefined symbol index, so always include it and start
    // processing symbols after it.
    entries.add(table.entries.get(0));

    // Fixup and add the remaining entries.
    RichStream.from(MoreIterables.enumerate(table.entries))
        .skip(1)
        // Generate a new sanitized symbol table entry.
        .map(
            pair ->
                new ElfSymbolTable.Entry(
                    pair.getSecond().st_name,
                    pair.getSecond().st_info,
                    pair.getSecond().st_other,
                    // A section index of 0 is special and means the symbol is undefined, so we
                    // must maintain that.  Otherwise, if it's non-zero, fix it up to an arbitrary
                    // stable section value so the number and ordering of sections can never affect
                    // the content of the symbol table.
                    pair.getSecond().st_shndx > 0 ? STABLE_SECTION : pair.getSecond().st_shndx,
                    // Substitute non-zero addresses, dependent on size/layout of sections with a
                    // stable address determined by the index of this symbol table entry in the
                    // symbol table.
                    pair.getSecond().st_value == 0 ? 0 : pair.getFirst(),
                    // For functions, set the size to zero.
                    pair.getSecond().st_info.st_type == ElfSymbolTable.Entry.Info.Type.STT_FUNC
                        ? 0
                        : pair.getSecond().st_size))
        .forEach(entries::add);

    return new ElfSymbolTable(entries.build());
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
      Optional<ElfSection> section = elf.getSectionByName(getSection()).map(Pair::getSecond);
      if (!section.isPresent()) {
        if (isAllowMissing()) {
          return StepExecutionResult.SUCCESS;
        } else {
          throw new IOException(
              String.format(
                  "Error parsing ELF file %s: no such section \"%s\"", getPath(), getSection()));
        }
      }

      // Read in and fixup the symbol table then write it back out.
      ElfSymbolTable table = ElfSymbolTable.parse(elf.header.ei_class, section.get().body);
      ElfSymbolTable fixedUpTable = fixUpSymbolTable(table);
      Preconditions.checkState(table.entries.size() == fixedUpTable.entries.size());
      section.get().body.rewind();
      fixedUpTable.write(elf.header.ei_class, section.get().body);
    }

    return StepExecutionResult.SUCCESS;
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
