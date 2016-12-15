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
import com.facebook.buck.cxx.elf.ElfHeader;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleTuple;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * A step which scrubs an ELF symbol table of information relevant to dynamic linking.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractElfSymbolTableScrubberStep implements Step {

  abstract ProjectFilesystem getFilesystem();
  abstract Path getPath();
  abstract String getSection();

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
      Optional<ElfSection> section = elf.getSectionByName(getSection());
      if (!section.isPresent()) {
        throw new IOException(
            String.format(
                "Error parsing ELF file %s: no such section \"%s\"",
                getPath(),
                getSection()));
      }

      // Iterate over each symbol table entry and zero out the address and size of each symbols.
      for (ByteBuffer body = section.get().body.duplicate(); body.hasRemaining(); ) {
        if (elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32) {
          Elf.Elf32.getElf32Word(body);  // st_name
          Elf.Elf32.putElf32Addr(body, 0);  // st_value
          Elf.Elf32.putElf32Word(body, 0);  // st_size
          body.get();  // st_info;
          body.get();  // st_other;
          Elf.Elf32.getElf32Half(body);  // st_shndx
        } else {
          Elf.Elf64.getElf64Word(body);  // st_name
          body.get();  // st_info;
          body.get();  // st_other;
          Elf.Elf64.getElf64Half(body);  // st_shndx
          Elf.Elf64.putElf64Addr(body, 0L);  // st_value;
          Elf.Elf64.putElf64Xword(body, 0L);  // st_size
        }
      }
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
