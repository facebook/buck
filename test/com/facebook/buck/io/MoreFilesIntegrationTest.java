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

package com.facebook.buck.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MoreFilesIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  @org.junit.Ignore

  public void testCopyTestdataDirectoryWithSymlinks() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "more_files", tmp);
    workspace.setUp();

    MoreFiles.copyRecursively(tmp.getRootPath().resolve("src"), tmp.getRootPath().resolve("out"));
    assertTrue(Files.isSymbolicLink(tmp.getRootPath().resolve("src/link.txt")));
    assertTrue(Files.isSymbolicLink(tmp.getRootPath().resolve("out/link.txt")));

    byte[] bytes = Files.readAllBytes(tmp.getRootPath().resolve("out/link.txt"));
    assertArrayEquals("contents\n".getBytes(), bytes);

    assertEquals(
        "link.txt should point to file.txt in the same directory.",
        Paths.get("file.txt"),
        Files.readSymbolicLink(tmp.getRootPath().resolve("out/link.txt")));

    Files.write(tmp.getRootPath().resolve("src/link.txt"), "replacement\n".getBytes());

    assertArrayEquals(
        "replacement\n".getBytes(),
        Files.readAllBytes(tmp.getRootPath().resolve("src/file.txt")));
    assertArrayEquals(
        "The replacement bytes should be reflected in the symlink.",
        "replacement\n".getBytes(),
        Files.readAllBytes(tmp.getRootPath().resolve("src/link.txt")));
    assertArrayEquals(
        "contents\n".getBytes(),
        Files.readAllBytes(tmp.getRootPath().resolve("out/file.txt")));
    assertArrayEquals(
        "The copied symlink should be unaffected.",
        "contents\n".getBytes(),
        Files.readAllBytes(tmp.getRootPath().resolve("out/link.txt")));
  }
}
