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

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfDynamicSection;
import com.facebook.buck.cxx.elf.ElfHeader;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ElfDynamicSectionScrubberStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void test() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
    ElfDynamicSectionScrubberStep step =
        ElfDynamicSectionScrubberStep.of(
            new ProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"));
    step.execute(TestExecutionContext.newInstance());

    // Verify that the relevant dynamic section tag have been zero'd out.
    try (FileChannel channel =
        FileChannel.open(step.getFilesystem().resolve(step.getPath()), StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      Optional<ElfSection> section =
          elf.getSectionByName(ElfDynamicSectionScrubberStep.SECTION).map(Pair::getSecond);
      for (ByteBuffer body = section.get().body; body.hasRemaining(); ) {
        ElfDynamicSection.DTag dTag =
            ElfDynamicSection.DTag.valueOf(
                elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32
                    ? Elf.Elf32.getElf32Sword(body)
                    : (int) Elf.Elf64.getElf64Sxword(body));
        long dPtr =
            elf.header.ei_class == ElfHeader.EIClass.ELFCLASS32
                ? Elf.Elf32.getElf32Addr(body)
                : Elf.Elf64.getElf64Addr(body);
        if (!ElfDynamicSectionScrubberStep.WHITELISTED_TAGS.contains(dTag)) {
          assertThat(dPtr, Matchers.equalTo(0L));
        }
      }
    }
  }
}
