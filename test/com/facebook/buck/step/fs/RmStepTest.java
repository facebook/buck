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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RmStepTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ExecutionContext context;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    context = TestExecutionContext.newInstance().withBuildCellRootPath(filesystem.getRootPath());
  }

  @Test
  public void deletesAFile() throws Exception {
    Path file = createFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, file));
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file));
  }

  @Test
  public void deletesADirectory() throws Exception {
    Path dir = createNonEmptyDirectory();

    RmStep step =
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    filesystem.getRootPath(), filesystem, dir))
            .withRecursive(true);
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(dir));
  }

  @Test
  public void recursiveModeWorksOnFiles() throws Exception {
    Path file = createFile();

    RmStep step =
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    filesystem.getRootPath(), filesystem, file))
            .withRecursive(true);
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file));
  }

  @Test
  public void nonRecursiveModeFailsOnDirectories() throws Exception {
    Path dir = createNonEmptyDirectory();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, dir));
    thrown.expect(DirectoryNotEmptyException.class);
    step.execute(context);
  }

  @Test
  public void deletingNonExistentFileSucceeds() throws Exception {
    Path file = getNonExistentFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, file));
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file));
  }

  @Test
  public void deletingNonExistentFileRecursivelySucceeds() throws Exception {
    Path file = getNonExistentFile();

    RmStep step =
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    filesystem.getRootPath(), filesystem, file))
            .withRecursive(true);
    assertEquals(0, step.execute(context).getExitCode());

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
