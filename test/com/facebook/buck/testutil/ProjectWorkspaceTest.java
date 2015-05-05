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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ProjectWorkspaceTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  @Test
  public void testWriteContentsToPath() throws IOException {
    File templateDir = Files.createTempDir();
    File testFile = new File(templateDir, "test.file");
    Files.write("hello world".getBytes(), testFile);

    ProjectWorkspace workspace = new ProjectWorkspace(templateDir, tmpFolder);
    workspace.writeContentsToPath("bye world", "test.file");

    assertEquals("bye world", Files.toString(workspace.getFile("test.file"), Charsets.UTF_8));
  }

  @Test
  public void testCopyRecursively() throws IOException {
    Path templateDir = java.nio.file.Files.createTempDirectory("template");
    Path sourceDir = java.nio.file.Files.createTempDirectory("source");
    Path testSubdir = sourceDir.resolve("subdir");
    java.nio.file.Files.createDirectory(testSubdir);
    Path testFile = testSubdir.resolve("test.file");
    java.nio.file.Files.write(testFile, ImmutableList.of("Hello world"), Charsets.UTF_8);
    Path testFile2 = testSubdir.resolve("test.file2");
    java.nio.file.Files.write(testFile2, ImmutableList.of("Goodbye world"), Charsets.UTF_8);

    ProjectWorkspace workspace = new ProjectWorkspace(templateDir, tmpFolder);
    workspace.copyRecursively(sourceDir, Paths.get("destdir"));

    assertEquals(
        "Hello world\n",
        Files.toString(workspace.getFile("destdir/subdir/test.file"), Charsets.UTF_8));
    assertEquals(
        "Goodbye world\n",
        Files.toString(workspace.getFile("destdir/subdir/test.file2"), Charsets.UTF_8));
  }
}
