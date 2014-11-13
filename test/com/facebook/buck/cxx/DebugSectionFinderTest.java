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

package com.facebook.buck.cxx;

import static com.facebook.buck.cxx.DebugSectionProperty.COMPRESSED;
import static com.facebook.buck.cxx.DebugSectionProperty.STRINGS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public class DebugSectionFinderTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private void assertDebugSections(
      Optional<ImmutableMap<String, ImmutableSet<DebugSectionProperty>>> expected,
      Optional<ImmutableMap<String, DebugSection>> actual) {
    assertTrue(
        (expected.isPresent() && actual.isPresent()) ||
        (!expected.isPresent() && !actual.isPresent()));
    if (expected.isPresent()) {
      assertEquals(expected.get().keySet(), actual.get().keySet());
      for (Map.Entry<String, DebugSection> entry : actual.get().entrySet()) {
        assertEquals(
            entry.getKey(),
            expected.get().get(entry.getKey()),
            entry.getValue().properties);
      }
    }
  }

  private void assertDebugSections(
      Optional<ImmutableMap<String, ImmutableSet<DebugSectionProperty>>> expected,
      ByteBuffer buffer) {
    DebugSectionFinder finder = new DebugSectionFinder();
    assertDebugSections(expected, finder.find(buffer));
  }

  private void assertDebugSections(
      Optional<ImmutableMap<String, ImmutableSet<DebugSectionProperty>>> expected,
      Path path)
      throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
      assertDebugSections(expected, buffer);
    }
  }

  @Test
  public void testElf() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "debug_sections", tmp);
    workspace.setUp();

    // No debug sections.
    assertDebugSections(
        Optional.of(ImmutableMap.<String, ImmutableSet<DebugSectionProperty>>of()),
        workspace.resolve("elf.o"));

    // DWARF
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".debug_line", ImmutableSet.of(STRINGS),
                ".debug_str", ImmutableSet.of(STRINGS))),
        workspace.resolve("elf-dwarf2.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".debug_line", ImmutableSet.of(STRINGS),
                ".debug_str", ImmutableSet.of(STRINGS))),
        workspace.resolve("elf-dwarf3.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".debug_line", ImmutableSet.of(STRINGS),
                ".debug_str", ImmutableSet.of(STRINGS))),
        workspace.resolve("elf-dwarf4.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".zdebug_line", ImmutableSet.of(STRINGS, COMPRESSED),
                ".zdebug_str", ImmutableSet.of(STRINGS, COMPRESSED))),
        workspace.resolve("elf-dwarf2-compressed.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".zdebug_line", ImmutableSet.of(STRINGS, COMPRESSED),
                ".zdebug_str", ImmutableSet.of(STRINGS, COMPRESSED))),
        workspace.resolve("elf-dwarf3-compressed.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".zdebug_line", ImmutableSet.of(STRINGS, COMPRESSED),
                ".zdebug_str", ImmutableSet.of(STRINGS, COMPRESSED))),
        workspace.resolve("elf-dwarf4-compressed.o"));

    // STABS
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".stabstr", ImmutableSet.of(STRINGS))),
        workspace.resolve("elf-stabs.o"));
    assertDebugSections(
        Optional.of(
            ImmutableMap.of(
                ".stabstr", ImmutableSet.of(STRINGS))),
        workspace.resolve("elf-stabs+.o"));
  }

  @Test
  public void testUnrecognizedData() {
    assertDebugSections(
        Optional.<ImmutableMap<String, ImmutableSet<DebugSectionProperty>>>absent(),
        ByteBuffer.wrap("some random data".getBytes()));
  }

}
