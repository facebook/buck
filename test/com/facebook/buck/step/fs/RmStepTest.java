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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RmStepTest {

  private ExecutionContext context;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws IOException {
    context = TestExecutionContext.newInstance();
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void deletesAFile() throws IOException {
    Path file = createFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  @Test
  public void deletesAFileWithForce() throws IOException {
    Path file = createFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  @Test
  public void deletesADirectory() throws IOException {
    Path dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        filesystem,
        dir,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(dir));
  }

  @Test
  public void deletesADirectoryWithForce() throws IOException {
    Path dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        filesystem,
        dir,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(dir));
  }

  @Test
  public void recursiveModeWorksOnFiles() throws IOException {
    Path file = createFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  @Test
  public void recursiveModeWithForceWorksOnFiles() throws IOException {
    Path file = createFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  @Test
  public void nonRecursiveModeFailsOnDirectories() throws IOException {
    Path dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        filesystem,
        dir,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void nonRecursiveModeWithForceFailsOnDirectories() throws IOException {
    Path dir = createNonEmptyDirectory();

    RmStep step = new RmStep(
        filesystem,
        dir,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileFails() throws IOException {
    Path file = getNonExistentFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ false);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileWithForceSucceeds() throws IOException {
    Path file = getNonExistentFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  @Test
  public void deletingNonExistentFileRecursivelyFails() throws IOException {
    Path file = getNonExistentFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ false,
        /* shouldRecurse */ true);
    assertEquals(1, step.execute(context));
  }

  @Test
  public void deletingNonExistentFileRecursivelyWithForceSucceeds() throws IOException {
    Path file = getNonExistentFile();

    RmStep step = new RmStep(
        filesystem,
        file,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ true);
    assertEquals(0, step.execute(context));

    assertFalse(Files.exists(file));
  }

  private Path createFile() throws IOException {
    Path file = Files.createTempFile(filesystem.getRootPath(), "buck", ".txt");
    Files.write(file, "blahblah".getBytes(UTF_8));
    assertTrue(Files.exists(file));
    return file;
  }

  private Path createNonEmptyDirectory() throws IOException {
    Path dir = Files.createTempDirectory(filesystem.getRootPath(), "buck");
    Path file = dir.resolve("file");
    Files.write(file, "blahblah".getBytes(UTF_8));
    assertTrue(Files.exists(dir));
    return dir;
  }

  private Path getNonExistentFile() {
    Path file = filesystem.getRootPath().resolve("does-not-exist");
    assertFalse(Files.exists(file));
    return file;
  }

}
