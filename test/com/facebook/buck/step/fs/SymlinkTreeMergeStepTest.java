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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SymlinkTreeMergeStepTest {
  @Rule public TemporaryPaths tempDir = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private Path linkPath;
  private final BiFunction<ProjectFilesystem, Path, Boolean> deleteExistingLinkPredicate =
      (fs, existingTarget) -> false;

  @Before
  public void setUp() throws IOException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    linkPath = filesystem.resolve("dest");

    filesystem.mkdirs(linkPath);

    filesystem.mkdirs(Paths.get("example_py"));
    filesystem.mkdirs(Paths.get("example_py", "common_dir"));
    filesystem.mkdirs(Paths.get("example_py", "example_py-1.0"));
    filesystem.mkdirs(Paths.get("example_2_py"));
    filesystem.mkdirs(Paths.get("example_2_py", "common_dir"));
    filesystem.mkdirs(Paths.get("example_2_py", "example_2_py-1.0"));
    filesystem.mkdirs(Paths.get("example_3_py"));
    filesystem.mkdirs(Paths.get("example_3_py", "common_dir"));
    filesystem.mkdirs(Paths.get("example_3_py", "example_3_py-1.0"));
    filesystem.writeContentsToPath(
        "print(\"example_py.py\")", Paths.get("example_py", "example_py.py"));
    filesystem.writeContentsToPath(
        "print(\"original.py\")", Paths.get("example_py", "common_dir", "original.py"));
    filesystem.writeContentsToPath(
        "Description goes here!", Paths.get("example_py", "example_py-1.0/DESCRIPTION.rst"));

    filesystem.writeContentsToPath(
        "print(\"example_2_py.py\")", Paths.get("example_2_py", "example_2_py.py"));
    filesystem.writeContentsToPath(
        "Other description goes here!",
        Paths.get("example_2_py", "example_2_py-1.0/DESCRIPTION.rst"));
    filesystem.writeContentsToPath(
        "print(\"sibling.py\")", Paths.get("example_2_py", "common_dir", "sibling.py"));

    filesystem.writeContentsToPath(
        "print(\"example_3_py.py\")", Paths.get("example_3_py", "example_3_py.py"));
    filesystem.writeContentsToPath(
        "Other description goes here!",
        Paths.get("example_3_py", "example_3_py-1.0/DESCRIPTION.rst"));
    filesystem.writeContentsToPath(
        "print(\"sibling.py\")", Paths.get("example_3_py", "common_dir", "sibling.py"));
  }

  @Test
  public void mergesFromDirectoriesProperly() throws IOException {
    SymlinkPaths dirs =
        SymlinkPackPaths.of(
            new SymlinkDirPaths(filesystem.resolve(Paths.get("example_py"))),
            new SymlinkDirPaths(filesystem.resolve(Paths.get("example_2_py"))));

    SymlinkTreeMergeStep step =
        new SymlinkTreeMergeStep("binary", filesystem, linkPath, dirs, deleteExistingLinkPredicate);
    StepExecutionResult result = step.execute(TestExecutionContext.newInstance());

    assertEquals(StepExecutionResults.SUCCESS, result);
    Assert.assertTrue(filesystem.isDirectory(linkPath.resolve("common_dir")));

    if (Platform.detect() != Platform.WINDOWS) {
      Assert.assertTrue(filesystem.isSymLink(linkPath.resolve(Paths.get("example_py.py"))));
      Assert.assertTrue(
          filesystem.isSymLink(linkPath.resolve(Paths.get("example_py-1.0", "DESCRIPTION.rst"))));
      Assert.assertTrue(
          filesystem.isSymLink(linkPath.resolve(Paths.get("common_dir", "original.py"))));

      Assert.assertTrue(filesystem.isSymLink(linkPath.resolve(Paths.get("example_2_py.py"))));
      Assert.assertTrue(
          filesystem.isSymLink(linkPath.resolve(Paths.get("example_2_py-1.0", "DESCRIPTION.rst"))));
      Assert.assertTrue(
          filesystem.isSymLink(linkPath.resolve(Paths.get("common_dir", "sibling.py"))));
    }

    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_py", "example_py.py")),
            filesystem.resolve(linkPath.resolve(Paths.get("example_py.py")))));
    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_py", "example_py-1.0", "DESCRIPTION.rst")),
            filesystem.resolve(linkPath.resolve(Paths.get("example_py-1.0", "DESCRIPTION.rst")))));
    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_py", "common_dir", "original.py")),
            filesystem.resolve(linkPath.resolve(Paths.get("common_dir", "original.py")))));

    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_2_py", "example_2_py.py")),
            filesystem.resolve(linkPath.resolve(Paths.get("example_2_py.py")))));
    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_2_py", "example_2_py-1.0", "DESCRIPTION.rst")),
            filesystem.resolve(
                linkPath.resolve(Paths.get("example_2_py-1.0", "DESCRIPTION.rst")))));
    Assert.assertTrue(
        Files.isSameFile(
            filesystem.resolve(Paths.get("example_2_py", "common_dir", "sibling.py")),
            filesystem.resolve(linkPath.resolve(Paths.get("common_dir", "sibling.py")))));
  }

  @Test
  public void throwsIfDestinationAlreadyExists() throws IOException {
    Path examplePyDest = linkPath.resolve("example_py.py");
    Path examplePySource = filesystem.resolve("example_py").resolve("example_py.py");

    thrown.expectMessage("Tried to link");
    thrown.expectMessage(examplePySource.toString());
    thrown.expectMessage(examplePyDest.toString());

    filesystem.writeContentsToPath("", examplePyDest);

    SymlinkTreeMergeStep step =
        new SymlinkTreeMergeStep(
            "binary",
            filesystem,
            linkPath,
            new SymlinkDirPaths(examplePySource.getParent()),
            deleteExistingLinkPredicate);
    step.execute(TestExecutionContext.newInstance());
  }

  @Test
  public void throwsWithBetterMessageIfDestinationAlreadyExistsAndIsASymlink() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Matchers.not(Platform.WINDOWS)));
    Path examplePyDest = linkPath.resolve("example_py.py");
    Path examplePySource = filesystem.resolve("example_py").resolve("example_py.py");
    Path otherPySource = filesystem.resolve("example_2_py").resolve("example_py.py");
    filesystem.writeContentsToPath("print(\"conflict!\")", otherPySource);
    String expectedMessage =
        String.format(
            "Tried to link %s to %s, but %s already links to %s",
            examplePyDest, otherPySource, examplePyDest, examplePySource);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(expectedMessage);

    SymlinkTreeMergeStep step =
        new SymlinkTreeMergeStep(
            "binary",
            filesystem,
            linkPath,
            SymlinkPackPaths.of(
                new SymlinkDirPaths(examplePySource.getParent()),
                new SymlinkDirPaths(otherPySource.getParent())),
            deleteExistingLinkPredicate);
    step.execute(TestExecutionContext.newInstance());
  }

  @Test
  public void linksIfFileAlreadyExistsAndIsASymlinkAndPredicateReturnsTrue() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Matchers.not(Platform.WINDOWS)));
    Path examplePyDest = linkPath.resolve("example_py.py");
    Path examplePySource = filesystem.resolve("example_py").resolve("example_py.py");
    Path otherPySource = filesystem.resolve("example_2_py").resolve("example_py.py");
    filesystem.writeContentsToPath("print(\"conflict!\")", otherPySource);

    SymlinkTreeMergeStep step =
        new SymlinkTreeMergeStep(
            "binary",
            filesystem,
            linkPath,
            SymlinkPackPaths.of(
                new SymlinkDirPaths(examplePySource.getParent()),
                new SymlinkDirPaths(otherPySource.getParent())),
            (fs, existingTarget) -> true);
    step.execute(TestExecutionContext.newInstance());

    assertThat(
        filesystem.readSymLink(examplePyDest), Matchers.oneOf(examplePySource, otherPySource));
  }
}
