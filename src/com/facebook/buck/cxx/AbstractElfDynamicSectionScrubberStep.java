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
import com.facebook.buck.cxx.elf.ElfDynamicSection;
import com.facebook.buck.cxx.elf.ElfHeader;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import org.immutables.value.Value;

/**
 * A step which scrubs all information from the ".dynamic" section of an ELF file which is relevant
 * at link time.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractElfDynamicSectionScrubberStep implements Step {

  static final String SECTION = ".dynamic";

  // We only care about these attributes -- zero out the rest.
  static final EnumSet<ElfDynamicSection.DTag> WHITELISTED_TAGS =
      EnumSet.of(ElfDynamicSection.DTag.DT_NEEDED, ElfDynamicSection.DTag.DT_SONAME);

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            getFilesystem().resolve(getPath()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfSection section = elf.getMandatorySectionByName(getPath(), SECTION);
      for (ByteBuffer body = section.body; body.hasRemaining(); ) {
        ElfDynamicSection.DTag dTag =
            ElfDynamicSection.DTag.valueOf(
                elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32
                    ? Elf.Elf32.getElf32Sword(body)
                    : (int) Elf.Elf64.getElf64Sxword(body));
        if (!WHITELISTED_TAGS.contains(dTag)) {
          if (elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32) {
            Elf.Elf32.putElf32Addr(body, 0); // d_ptr
          } else {
            Elf.Elf64.putElf64Addr(body, 0); // d_ptr
          }
        } else {
          if (elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32) {
            Elf.Elf32.getElf32Addr(body); // d_ptr
          } else {
            Elf.Elf64.getElf64Addr(body); // d_ptr
          }
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
    return "Scrub ELF symbol table in " + getPath();
  }
}
