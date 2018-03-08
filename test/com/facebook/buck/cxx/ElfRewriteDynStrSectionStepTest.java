/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ElfRewriteDynStrSectionStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
  }

  private ImmutableSet<String> readDynStr(Path path) throws IOException {
    ImmutableSet.Builder<String> strings = ImmutableSet.builder();
    try (FileChannel channel = FileChannel.open(workspace.resolve(path))) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfSection section = elf.getMandatorySectionByName(path, ".dynstr").getSection();

      StringBuilder builder = new StringBuilder();
      while (section.body.hasRemaining()) {
        char c = (char) section.body.get();
        if (c == '\0') {
          strings.add(builder.toString());
          builder = new StringBuilder();
        } else {
          builder.append(c);
        }
      }
    }
    return strings.build();
  }

  @Test
  public void test() throws IOException {
    Path lib = tmp.getRoot().getFileSystem().getPath("libfoo.so");
    ImmutableSet<String> originalStrings = readDynStr(lib);
    ElfRewriteDynStrSectionStep step =
        ElfRewriteDynStrSectionStep.of(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()), lib);
    step.execute(TestExecutionContext.newInstance());
    ImmutableSet<String> rewrittenStrings = readDynStr(lib);
    // Verify that the rewritten string table is a subset of the original.
    assertThat(rewrittenStrings, Matchers.everyItem(Matchers.in(originalStrings)));
  }
}
