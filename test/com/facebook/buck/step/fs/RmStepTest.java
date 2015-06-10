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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class RmStepTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private ExecutionContext context;

  @Before
  public void setUp() {
    context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmpDir.getRoot().toPath()))
        .build();
  }

  @Test
  public void deletesAFile() throws IOException {
    File file = createFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  @Test
  public void deletesAFileWithForce() throws IOException {
    File file = createFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  @Test
  public void deletesADirectory() throws IOException {
    File dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        dir.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(dir.exists());
  }

  @Test
  public void deletesADirectoryWithForce() throws IOException {
    File dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        dir.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(dir.exists());
  }

  @Test
  public void recursiveModeWorksOnFiles() throws IOException {
    File file = createFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  @Test
  public void recursiveModeWithForceWorksOnFiles() throws IOException {
    File file = createFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  @Test
  public void nonRecursiveModeFailsOnDirectories() throws IOException {
    File dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        dir.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void nonRecursiveModeWithForceFailsOnDirectories() throws IOException {
    File dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        dir.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileFails() throws IOException {
    File file = getNonExistentFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileWithForceSucceeds() throws IOException {
    File file = getNonExistentFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  @Test
  public void deletingNonExistentFileRecursivelyFails() throws IOException {
    File file = getNonExistentFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileRecursivelyWithForceSucceeds() throws IOException {
    File file = getNonExistentFile();

    RmStep step = new RmStep(
        file.toPath(),
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(file.exists());
  }

  private File createFile() throws IOException {
    File file = tmpDir.newFile();
    Files.write("blahblah", file, Charsets.UTF_8);
    assertTrue(file.exists());
    return file;
  }

  private File createNonEmptyDirectory() throws IOException {
    File dir = tmpDir.newFolder();
    File file = dir.toPath().resolve("file").toFile();
    Files.write("blahblah", file, Charsets.UTF_8);
    assertTrue(dir.exists());
    return dir;
  }

  private File getNonExistentFile() {
    File file = tmpDir.getRoot().toPath().resolve("foo").toFile();
    assertFalse(file.exists());
    return file;
  }

}
