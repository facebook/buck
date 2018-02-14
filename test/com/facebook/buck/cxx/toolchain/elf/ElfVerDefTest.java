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

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ElfVerDefTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "samples", tmp);
    workspace.setUp();
  }

  @Test
  public void test() throws IOException {
    try (FileChannel channel = FileChannel.open(workspace.resolve("libfoo.so"))) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfSection stringTable =
          elf.getMandatorySectionByName(channel.toString(), ".dynstr").getSection();
      ElfSection section =
          elf.getMandatorySectionByName(channel.toString(), ".gnu.version_d").getSection();
      assertThat(section.header.sh_type, Matchers.is(ElfSectionHeader.SHType.SHT_GNU_VERDEF));
      ElfVerDef verDef = ElfVerDef.parse(elf.header.ei_class, section.body);
      assertThat(verDef.entries, Matchers.hasSize(2));
      assertThat(
          stringTable.lookupString(verDef.entries.get(0).getSecond().get(0).vda_name),
          Matchers.equalTo("libfoo.so"));
      assertThat(
          stringTable.lookupString(verDef.entries.get(1).getSecond().get(0).vda_name),
          Matchers.equalTo("VERS_1.0"));
    }
  }
}
