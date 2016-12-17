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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfHeader;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public class ElfSymbolTableScrubberStepTest {

  private static final String SECTION = ".dynsym";

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void test() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
    ElfSymbolTableScrubberStep step =
        ElfSymbolTableScrubberStep.of(
            new ProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"),
            ".dynsym",
            /* allowMissing */ false);
    step.execute(TestExecutionContext.newInstance());

    // Verify that the symbol table values and sizes are zero.
    try (FileChannel channel =
        FileChannel.open(
            step.getFilesystem().resolve(step.getPath()),
            StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      Optional<ElfSection> section = elf.getSectionByName(SECTION);
      assertTrue(section.isPresent());
      long address = 1;
      for (ByteBuffer body = section.get().body; body.hasRemaining(); ) {
        long stValue;
        long stSize;
        if (elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32) {
          Elf.Elf32.getElf32Word(body);  // st_name
          stValue = Elf.Elf32.getElf32Addr(body);  // st_value
          stSize = Elf.Elf32.getElf32Word(body);  // st_size
          body.get();  // st_info;
          body.get();  // st_other;
          Elf.Elf32.getElf32Half(body);  // st_shndx
        } else {
          Elf.Elf64.getElf64Word(body);  // st_name
          body.get();  // st_info;
          body.get();  // st_other;
          Elf.Elf64.getElf64Half(body);  // st_shndx
          stValue = Elf.Elf64.getElf64Addr(body);  // st_value;
          stSize = Elf.Elf64.getElf64Xword(body);  // st_size
        }
        assertThat(stValue, Matchers.oneOf(0L, address));
        assertThat(stSize, Matchers.oneOf(0L, ElfSymbolTableScrubberStep.STABLE_SIZE));
        if (stValue > 0) {
          address++;
        }
      }
    }
  }

}
