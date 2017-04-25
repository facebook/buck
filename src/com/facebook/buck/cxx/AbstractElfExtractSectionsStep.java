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

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.immutables.value.Value;

/**
 * A step which extracts specific sections from an ELF file and compacts them into a new ELF file.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractElfExtractSectionsStep implements Step {

  abstract ProjectFilesystem getFilesystem();

  abstract ImmutableList<String> getObjcopyPrefix();

  abstract Path getInput();

  abstract Path getOutput();

  abstract ImmutableSet<String> getSections();

  // We want to compact the sections into the new ELF file, so find out the new addresses of each
  // section.
  private ImmutableMap<String, Long> getNewSectionAddresses() throws IOException {
    ImmutableMap.Builder<String, Long> addresses = ImmutableMap.builder();
    try (FileChannel channel =
        FileChannel.open(getFilesystem().resolve(getInput()), StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);

      // We start placing sections right after the program headers.
      long end = elf.header.e_phoff + elf.header.e_phnum * elf.header.e_phentsize;
      for (int index = 0; index < elf.getNumberOfSections(); index++) {
        ElfSection section = elf.getSectionByIndex(index);
        String name = elf.getSectionName(section.header);
        // If this is a target section, assign it the current address, then increment the next
        // address by this sections size.
        if (getSections().contains(name)) {
          addresses.put(name, end);
          end += section.header.sh_size;
        }
      }
    }
    return addresses.build();
  }

  private ImmutableList<String> getObjcopyCommand(ImmutableMap<String, Long> addresses) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(getObjcopyPrefix());
    for (String section : getSections()) {
      Long address = addresses.get(section);
      if (address != null) {
        args.add(
            "--only-section",
            section,
            "--change-section-address",
            String.format("%s=0x%x", section, address));
      }
    }
    args.add(getInput().toString());
    args.add(getOutput().toString());
    return args.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableMap<String, Long> addresses = getNewSectionAddresses();
    Step objcopy =
        new DefaultShellStep(
            getFilesystem().getRootPath(),
            /* args */ getObjcopyCommand(addresses),
            /* env */ ImmutableMap.of());
    return objcopy.execute(context);
  }

  @Override
  public final String getShortName() {
    return "scrub_symbol_table";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "Extract sections %s from %s", Joiner.on(", ").join(getSections()), getInput());
  }
}
