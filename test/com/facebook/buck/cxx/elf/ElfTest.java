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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ElfTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void le64() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "samples", tmp);
    workspace.setUp();

    Path elfPath = workspace.resolve(Paths.get("le64.o"));
    try (FileChannel channel = FileChannel.open(elfPath)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      assertEquals(ElfHeader.EIClass.ELFCLASS64, elf.header.ei_class);
      assertEquals(ElfHeader.EIData.ELFDATA2LSB, elf.header.ei_data);
      assertEquals(11, elf.getNumberOfSections());
      assertTrue(elf.getSectionByName(".text").isPresent());
    }

  }

  @Test
  public void le32() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "samples", tmp);
    workspace.setUp();

    Path elfPath = workspace.resolve(Paths.get("le32.o"));
    try (FileChannel channel = FileChannel.open(elfPath)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      assertEquals(ElfHeader.EIClass.ELFCLASS32, elf.header.ei_class);
      assertEquals(ElfHeader.EIData.ELFDATA2LSB, elf.header.ei_data);
      assertEquals(9, elf.getNumberOfSections());
      assertTrue(elf.getSectionByName(".text").isPresent());
    }

  }

  @Test
  public void be32() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "samples", tmp);
    workspace.setUp();

    Path elfPath = workspace.resolve(Paths.get("be32.o"));
    try (FileChannel channel = FileChannel.open(elfPath)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      assertEquals(ElfHeader.EIClass.ELFCLASS32, elf.header.ei_class);
      assertEquals(ElfHeader.EIData.ELFDATA2MSB, elf.header.ei_data);
      assertEquals(14, elf.getNumberOfSections());
      assertTrue(elf.getSectionByName(".text").isPresent());
    }

  }

  @Test
  public void sectionTypes() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "samples", tmp);
    workspace.setUp();

    Path elfPath = workspace.resolve(Paths.get("section_types.o"));
    try (FileChannel channel = FileChannel.open(elfPath)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      Optional<ElfSection> section;

      section = elf.getSectionByName(".text");
      assertTrue(section.isPresent());
      assertEquals(ElfSectionHeader.SHType.SHT_PROGBITS, section.get().header.sh_type);

      section = elf.getSectionByName(".bss");
      assertTrue(section.isPresent());
      assertEquals(ElfSectionHeader.SHType.SHT_NOBITS, section.get().header.sh_type);

      section = elf.getSectionByName(".strtab");
      assertTrue(section.isPresent());
      assertEquals(ElfSectionHeader.SHType.SHT_STRTAB, section.get().header.sh_type);

      section = elf.getSectionByName(".symtab");
      assertTrue(section.isPresent());
      assertEquals(ElfSectionHeader.SHType.SHT_SYMTAB, section.get().header.sh_type);
    }

  }

}
