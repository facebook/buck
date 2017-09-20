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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class InputPathTypeCoercerTest {
  private FakeProjectFilesystem projectFilesystem;
  private CellPathResolver cellRoots;
  private final Path pathRelativeToProjectRoot = Paths.get("");
  private final InputPathTypeCoercer inputPathTypeCoercer =
      new InputPathTypeCoercer(
          new SourcePathTypeCoercer(
              new BuildTargetTypeCoercer(),
              new PathTypeCoercer(PathTypeCoercer.PathExistenceVerificationMode.VERIFY)));

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    cellRoots = TestCellPathResolver.get(projectFilesystem);
  }

  @Before
  public void setUpCellRoots() {}

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void coercePath() throws CoerceFailedException, IOException {
    String path = "hello.a";
    projectFilesystem.touch(Paths.get(path));

    InputPath input =
        inputPathTypeCoercer.coerce(cellRoots, projectFilesystem, pathRelativeToProjectRoot, path);

    assertEquals(new InputPath(new PathSourcePath(projectFilesystem, Paths.get(path))), input);
  }

  @Test
  public void coerceBuildTarget() throws CoerceFailedException {
    InputPath path =
        inputPathTypeCoercer.coerce(
            cellRoots, projectFilesystem, pathRelativeToProjectRoot, "//:hello");

    assertEquals(
        new InputPath(
            new DefaultBuildTargetSourcePath(
                BuildTarget.of(
                    UnflavoredBuildTarget.of(
                        projectFilesystem.getRootPath(), Optional.empty(), "//", "hello"),
                    ImmutableSortedSet.of()))),
        path);
  }
}
