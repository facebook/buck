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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildFileSpecTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void recursiveVsNonRecursive() throws IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.touch(buildFile);

    Path nestedBuildFile = Paths.get("a", "b", "BUCK");
    filesystem.mkdirs(nestedBuildFile.getParent());
    filesystem.touch(nestedBuildFile);

    // Test a non-recursive spec.
    BuildFileSpec nonRecursiveSpec = BuildFileSpec.fromPath(buildFile.getParent());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ImmutableSet<Path> actualBuildFiles = nonRecursiveSpec.findBuildFiles(cell);
    assertEquals(expectedBuildFiles, actualBuildFiles);

    // Test a recursive spec.
    BuildFileSpec recursiveSpec = BuildFileSpec.fromRecursivePath(buildFile.getParent());
    expectedBuildFiles =
        ImmutableSet.of(filesystem.resolve(buildFile), filesystem.resolve(nestedBuildFile));
    actualBuildFiles = recursiveSpec.findBuildFiles(cell);
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void recursiveIgnorePaths() throws IOException, InterruptedException {
    Path ignoredBuildFile = Paths.get("a", "b", "BUCK");
    ImmutableSet<Path> ignore = ImmutableSet.of(ignoredBuildFile.getParent());
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath(), ignore);
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.writeContentsToPath("", buildFile);


    filesystem.mkdirs(ignoredBuildFile.getParent());
    filesystem.writeContentsToPath("", ignoredBuildFile);

    // Test a recursive spec with an ignored dir.

    BuildFileSpec recursiveSpec = BuildFileSpec.fromRecursivePath(buildFile.getParent());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ImmutableSet<Path> actualBuildFiles = recursiveSpec.findBuildFiles(cell);
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

}

