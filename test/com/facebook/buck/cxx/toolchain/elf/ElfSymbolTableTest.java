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

package com.facebook.buck.cxx.toolchain.elf;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.ElfFile;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ElfSymbolTableTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "samples", tmp);
    workspace.setUp();
  }

  @Test
  public void le64() throws IOException {
    ElfSymbolTable symbolTable = parseSymbolTable("le64.o");
    ElfSymbolTable.Entry someEntry = symbolTable.entries.get(1);
    assertThat(someEntry.st_value, Matchers.equalTo(0L));
    assertThat(someEntry.st_size, Matchers.equalTo(0L));
    assertThat(someEntry.st_shndx, Matchers.equalTo(0xFFF1));
  }

  @Test
  public void le32() throws IOException {
    ElfSymbolTable symbolTable = parseSymbolTable("le32.o");
    ElfSymbolTable.Entry someEntry = symbolTable.entries.get(1);
    assertThat(someEntry.st_value, Matchers.equalTo(0L));
    assertThat(someEntry.st_size, Matchers.equalTo(0L));
    assertThat(someEntry.st_shndx, Matchers.equalTo(0xFFF1));
  }

  @Test
  public void be32() throws IOException {
    ElfSymbolTable symbolTable = parseSymbolTable("be32.o");
    ElfSymbolTable.Entry someEntry = symbolTable.entries.get(1);
    assertThat(someEntry.st_value, Matchers.equalTo(0L));
    assertThat(someEntry.st_size, Matchers.equalTo(0L));
    assertThat(someEntry.st_shndx, Matchers.equalTo(0xFFF1));
  }

  private ElfSymbolTable parseSymbolTable(String file) throws IOException {
    Elf elf = ElfFile.mapReadOnly(workspace.resolve(file));
    return ElfSymbolTable.parse(
        elf.header.ei_class,
        elf.getSectionByName(".symtab").orElseThrow(RuntimeException::new).getSection().body);
  }
}
