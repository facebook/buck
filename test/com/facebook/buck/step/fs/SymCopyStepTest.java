/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SymCopyStepTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private ExecutionContext context;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());
    context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
  }

  @Test
  public void testGetShortName() {
    SymCopyStep symCopyStep = new SymCopyStep(
        ImmutableList.<Path>builder().add(Paths.get("here")).build(),
        Paths.get("there"));
    assertEquals("lns", symCopyStep.getShortName());
  }

  @Test
  public void testDescription() {
    SymCopyStep symCopyStep = new SymCopyStep(
        ImmutableList.<Path>builder().add(Paths.get("here")).build(),
        Paths.get("there"));
    assertEquals("Symlink-Copy step", symCopyStep.getDescription(context));
  }

  @Test
  public void testSymCopyStep() throws IOException, InterruptedException {
    Path sourceRoot = Paths.get("src-root");
    projectFilesystem.mkdirs(sourceRoot);

    Path subDir1 = sourceRoot.resolve("dir1");
    projectFilesystem.mkdirs(subDir1);

    Path subDir2 = sourceRoot.resolve("dir2");
    projectFilesystem.mkdirs(subDir2);

    Path file1 = sourceRoot.resolve("file1");
    Path file2 = subDir1.resolve("file2");
    Path file3 = subDir2.resolve("file3");

    projectFilesystem.writeContentsToPath("foo1", file1);
    projectFilesystem.writeContentsToPath("foo2", file2);
    projectFilesystem.writeContentsToPath("foo3", file3);


    Path destRoot = Paths.get("dest-root");

    SymCopyStep symCopyStep = new SymCopyStep(
        ImmutableList.<Path>builder()
            .add(sourceRoot)
            .build(),
        destRoot);

    symCopyStep.execute(context);

    // check that the new directory structure is generated
    assertTrue(projectFilesystem.isDirectory(destRoot.resolve("dir1")));
    assertTrue(projectFilesystem.isDirectory(destRoot.resolve("dir2")));
    assertTrue(projectFilesystem.isSymLink(destRoot.resolve("file1")));
    assertTrue(projectFilesystem.isSymLink(destRoot.resolve("dir1").resolve("file2")));
    assertTrue(projectFilesystem.isSymLink(destRoot.resolve("dir2").resolve("file3")));

    // check that the symlinks generated are correct
    assertTrue(projectFilesystem.readSymLink(destRoot.resolve("file1")).equals(
            projectFilesystem.getAbsolutifier().apply(file1)));
    assertTrue(projectFilesystem.readSymLink(destRoot.resolve("dir1").resolve("file2")).equals(
            projectFilesystem.getAbsolutifier().apply(file2)));
    assertTrue(projectFilesystem.readSymLink(destRoot.resolve("dir2").resolve("file3")).equals(
            projectFilesystem.getAbsolutifier().apply(file3)));



  }

}
