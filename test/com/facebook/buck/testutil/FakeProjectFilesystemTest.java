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

package com.facebook.buck.testutil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class FakeProjectFilesystemTest {
  @Test
  public void testFilesystemReturnsAddedContents() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("Some content", Paths.get("A.txt"));

    Optional<String> contents;
    contents = filesystem.readFileIfItExists(Paths.get("A.txt"));
    assertTrue("Fake file system must return added file contents.", contents.isPresent());
    assertEquals("Some content", contents.get());

    contents = filesystem.readFileIfItExists(Paths.get("B.txt"));
    assertFalse("Fake file system must not return non-existing file contents",
        contents.isPresent());
  }

  @Test
  public void testReadLines() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("line one.\nline two.\n", Paths.get("A.txt"));
    filesystem.writeLinesToPath(ImmutableList.<String>of(), Paths.get("B.txt"));
    filesystem.writeContentsToPath("\n", Paths.get("C.txt"));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of("line one.", "line two."),
        filesystem.readLines(Paths.get("A.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(),
        filesystem.readLines(Paths.get("B.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(""),
        filesystem.readLines(Paths.get("C.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(),
        filesystem.readLines(Paths.get("D.txt")));
  }
}
