/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.step.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableSet;
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

  private StepExecutionContext context;
  private ProjectFilesystem filesystem;

  private StepExecutionContext tmpFilesystemContext;
  private ProjectFilesystem tmpFilesystem;

  @Before
  public void setUp() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    AbsPath rootPath = filesystem.getRootPath();
    context =
        StepExecutionContext.builder()
            .from(executionContext)
            .setBuildCellRootPath(rootPath.getPath())
            .setRuleCellRoot(rootPath)
            .build();

    // Real fs required to make certain APIs work as expected
    tmpFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    StepExecutionContext tmpFileExecutionContext = TestExecutionContext.newInstance();
    tmpFilesystemContext =
        StepExecutionContext.builder()
            .from(tmpFileExecutionContext)
            .setBuildCellRootPath(tmpFilesystem.getRootPath().getPath())
            .build();
  }

  @Test
  public void deletesAFile() throws Exception {
    Path file = createFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, file));
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file));
    assertEquals("rm -f " + file, step.getDescription(context));
  }

  @Test
  public void deletesADirectory() throws Exception {
    Path dir = createNonEmptyDirectory();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, dir),
            true);
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(dir));
  }

  @Test
  public void recursiveModeWorksOnFiles() throws Exception {
    Path file = createFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(filesystem.getRootPath(), filesystem, file),
            true);
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file));
    assertEquals("rm -f -r " + file, step.getDescription(context));
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
    AbsPath file = getNonExistentFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                filesystem.getRootPath(), filesystem, file.getPath()));
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file.getPath()));
  }

  @Test
  public void deletingNonExistentFileRecursivelySucceeds() throws Exception {
    AbsPath file = getNonExistentFile();

    RmStep step =
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                filesystem.getRootPath(), filesystem, file.getPath()),
            true);
    assertEquals(0, step.execute(context).getExitCode());

    assertFalse(Files.exists(file.getPath()));
  }

  @Test
  public void excludeNonExistentFile() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //     - file1
    //     - file2
    //   - file3
    //   - file4
    // Exclude: dir1/dir3/file5

    String file1Name = "dir1/dir2/file1";
    String file2Name = "dir1/dir2/file2";
    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir =
        createDirectoryForFileNames(tmpFilesystem, file1Name, file2Name, file3Name, file4Name);
    Path file1Path = rootDir.resolve(file1Name);
    Path file2Path = rootDir.resolve(file2Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            ImmutableSet.of(tmpFilesystem.relativize(rootDir.resolve("dir1/dir3/file5"))));

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check that the root dir has been deleted

    assertFalse(Files.exists(rootDir));
  }

  @Test
  public void excludeAllFilesInDir() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //     - file1
    //     - file2
    //   - file3
    //   - file4
    // Exclude: dir1/dir2/file1, dir1/dir2/file2

    String file1Name = "dir1/dir2/file1";
    String file2Name = "dir1/dir2/file2";
    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir =
        createDirectoryForFileNames(tmpFilesystem, file1Name, file2Name, file3Name, file4Name);
    Path file1Path = rootDir.resolve(file1Name);
    Path file2Path = rootDir.resolve(file2Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    ImmutableSet<RelPath> excludedPaths =
        ImmutableSet.of(tmpFilesystem.relativize(file1Path), tmpFilesystem.relativize(file2Path));

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            excludedPaths);

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check that file3 + file4 are deleted

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertFalse(Files.exists(file3Path));
    assertFalse(Files.exists(file4Path));
  }

  @Test
  public void excludeStrictSubsetOfFilesInDir() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //     - file1
    //     - file2
    //   - file3
    //   - file4
    // Exclude: dir1/dir2/file1

    String file1Name = "dir1/dir2/file1";
    String file2Name = "dir1/dir2/file2";
    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir =
        createDirectoryForFileNames(tmpFilesystem, file1Name, file2Name, file3Name, file4Name);
    Path file1Path = rootDir.resolve(file1Name);
    Path file2Path = rootDir.resolve(file2Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    RelPath excludedPath = tmpFilesystem.relativize(file1Path);

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            ImmutableSet.of(excludedPath));

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check that file2, file3 and file4 are deleted

    assertTrue(Files.exists(file1Path));
    assertFalse(Files.exists(file2Path));
    assertFalse(Files.exists(file3Path));
    assertFalse(Files.exists(file4Path));
  }

  @Test
  public void excludeEmptyDirectory() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //   - file3
    //   - file4
    // Exclude: dir1/dir2

    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir = createDirectoryForFileNames(tmpFilesystem, file3Name, file4Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);
    Path dir2Path = rootDir.resolve("dir2");

    Files.createDirectory(dir2Path);

    RelPath excludedPath = tmpFilesystem.relativize(dir2Path);

    assertTrue(Files.exists(dir2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            ImmutableSet.of(excludedPath));

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check that file3 and file4 are deleted, dir2 preserved

    assertTrue(Files.exists(dir2Path));
    assertFalse(Files.exists(file3Path));
    assertFalse(Files.exists(file4Path));
  }

  @Test
  public void excludeNonEmptyDirectory() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //     - file1
    //     - file2
    //   - file3
    //   - file4
    // Exclude: dir1/dir2

    String file1Name = "dir1/dir2/file1";
    String file2Name = "dir1/dir2/file2";
    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir =
        createDirectoryForFileNames(tmpFilesystem, file1Name, file2Name, file3Name, file4Name);
    Path file1Path = rootDir.resolve(file1Name);
    Path file2Path = rootDir.resolve(file2Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    RelPath excludedPath = tmpFilesystem.relativize(rootDir.resolve("dir1/dir2"));

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            ImmutableSet.of(excludedPath));

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check all files outside of dir1/dir2 were deleted

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertFalse(Files.exists(file3Path));
    assertFalse(Files.exists(file4Path));
  }

  @Test
  public void excludeRootDirectory() throws IOException, InterruptedException {
    // - dir1
    //   - dir2
    //     - file1
    //     - file2
    //   - file3
    //   - file4
    // Exclude: dir1

    String file1Name = "dir1/dir2/file1";
    String file2Name = "dir1/dir2/file2";
    String file3Name = "dir1/file3";
    String file4Name = "dir1/file4";

    Path rootDir =
        createDirectoryForFileNames(tmpFilesystem, file1Name, file2Name, file3Name, file4Name);
    Path file1Path = rootDir.resolve(file1Name);
    Path file2Path = rootDir.resolve(file2Name);
    Path file3Path = rootDir.resolve(file3Name);
    Path file4Path = rootDir.resolve(file4Name);

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));

    RelPath excludeRootDirPath = tmpFilesystem.relativize(rootDir);

    RmStep step =
        RmStep.recursiveOf(
            BuildCellRelativePath.fromCellRelativePath(
                tmpFilesystem.getRootPath(), tmpFilesystem, rootDir),
            ImmutableSet.of(excludeRootDirPath));

    assertEquals(0, step.execute(tmpFilesystemContext).getExitCode());

    // Check that no files were deleted

    assertTrue(Files.exists(file1Path));
    assertTrue(Files.exists(file2Path));
    assertTrue(Files.exists(file3Path));
    assertTrue(Files.exists(file4Path));
  }

  private static Path createDirectoryForFileNames(ProjectFilesystem filesystem, String... fileNames)
      throws IOException {
    Path dir = Files.createTempDirectory(filesystem.getRootPath().getPath(), "buck");
    assertTrue(Files.exists(dir));

    for (String fileName : fileNames) {
      Path filePath = dir.resolve(fileName);
      Files.createDirectories(filePath.getParent());
      Files.write(filePath, fileName.getBytes(UTF_8));
      assertTrue(Files.exists(filePath));
    }

    return dir;
  }

  private Path createFile() throws IOException {
    Path file = Files.createTempFile(filesystem.getRootPath().getPath(), "buck", ".txt");
    Files.write(file, "blahblah".getBytes(UTF_8));
    assertTrue(Files.exists(file));
    return file;
  }

  private Path createNonEmptyDirectory() throws IOException {
    Path dir = Files.createTempDirectory(filesystem.getRootPath().getPath(), "buck");
    Path file = dir.resolve("file");
    Files.write(file, "blahblah".getBytes(UTF_8));
    assertTrue(Files.exists(dir));
    return dir;
  }

  private AbsPath getNonExistentFile() {
    AbsPath file = filesystem.getRootPath().resolve("does-not-exist");
    assertFalse(Files.exists(file.getPath()));
    return file;
  }
}
