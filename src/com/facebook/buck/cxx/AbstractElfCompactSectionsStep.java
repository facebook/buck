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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.immutables.value.Value;

/**
 * A step which extracts specific sections from an ELF file and compacts them into a new ELF file.
 */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfCompactSectionsStep implements Step {

  private static final long SHF_ALLOC = 0x2L;

  abstract BuildTarget getBuildTarget();

  abstract ImmutableList<String> getObjcopyPrefix();

  abstract ProjectFilesystem getInputFilesystem();

  abstract Path getInput();

  abstract ProjectFilesystem getOutputFilesystem();

  abstract Path getOutput();

  @Value.Check
  void check() {
    Preconditions.checkState(!getInput().isAbsolute());
    Preconditions.checkState(!getOutput().isAbsolute());
  }

  // We want to compact the sections into the new ELF file, so find out the new addresses of each
  // section.
  private ImmutableMap<String, Long> getNewSectionAddresses() throws IOException {
    ImmutableMap.Builder<String, Long> addresses = ImmutableMap.builder();
    try (FileChannel channel =
        FileChannel.open(getInputFilesystem().resolve(getInput()), StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);

      // We start placing sections right after the program headers.
      long end = elf.header.e_phoff + elf.header.e_phnum * elf.header.e_phentsize;
      for (int index = 0; index < elf.getNumberOfSections(); index++) {
        ElfSection section = elf.getSectionByIndex(index);
        String name = elf.getSectionName(section.header);
        if ((section.header.sh_flags & SHF_ALLOC) != 0) {
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
    args.add("--no-change-warnings");
    for (Map.Entry<String, Long> ent : addresses.entrySet()) {
      String section = ent.getKey();
      long address = ent.getValue();
      args.add("--change-section-address", String.format("%s=0x%x", section, address));
    }
    args.add(getInputFilesystem().resolve(getInput()).toString());
    args.add(getOutputFilesystem().resolve(getOutput()).toString());
    return args.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableMap<String, Long> addresses = getNewSectionAddresses();
    Step objcopy =
        new DefaultShellStep(
            getOutputFilesystem().getRootPath(),
            /* args */ getObjcopyCommand(addresses),
            /* env */ ImmutableMap.of());
    return objcopy.execute(context);
  }

  @Override
  public final String getShortName() {
    return "elf_compact_sections";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("Compact ELF sections in %s", getInputFilesystem().resolve(getInput()));
  }
}
