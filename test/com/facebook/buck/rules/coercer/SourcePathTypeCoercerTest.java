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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathTypeCoercerTest {
  private FakeProjectFilesystem projectFilesystem;
  private CellPathResolver cellRoots;
  private final Path pathRelativeToProjectRoot = Paths.get("");
  private final SourcePathTypeCoercer sourcePathTypeCoercer =
      new SourcePathTypeCoercer(new BuildTargetTypeCoercer(), new PathTypeCoercer());

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();

    cellRoots = new CellPathResolver() {
      @Override
      public Path getCellPath(Optional<String> cellName) {
        if (cellName.isPresent()) {
          throw new HumanReadableException("Boom");
        }
        return projectFilesystem.getRootPath();
      }
    };

  }

  @Before
  public void setUpCellRoots() {
  }



  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void coercePath() throws CoerceFailedException, IOException {
    String path = "hello.a";
    projectFilesystem.touch(Paths.get(path));

    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        path);

    assertEquals(new PathSourcePath(projectFilesystem, Paths.get(path)), sourcePath);
  }

  @Test
  public void coerceAbsoluteBuildTarget() throws CoerceFailedException, IOException {
    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        "//:hello");

    assertEquals(
        new BuildTargetSourcePath(
            BuildTarget.of(
                UnflavoredBuildTarget.of(
                    projectFilesystem.getRootPath(),
                    Optional.<String>absent(),
                    "//",
                    "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coerceRelativeBuildTarget() throws CoerceFailedException, IOException {
    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        ":hello");

    assertEquals(
        new BuildTargetSourcePath(
            BuildTarget.of(
                UnflavoredBuildTarget.of(
                    projectFilesystem.getRootPath(),
                      Optional.<String>absent(),
                      "//",
                      "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coerceCrossRepoBuildTarget() throws CoerceFailedException, IOException {
    final Path helloRoot = Paths.get("/opt/src/hello");

    cellRoots = new CellPathResolver() {
      @Override
      public Path getCellPath(Optional<String> input) {
        if (!input.isPresent()) {
          return projectFilesystem.getRootPath();
        }

        if ("hello".equals(input.get())) {
          return helloRoot;
        }

        throw new RuntimeException("Boom!");
      }
    };

    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        "hello//:hello");

    // Note that the important thing is that the root of the target has been set to `helloRoot` so
    // the cell name should be absent (otherwise, we'd look for a cell named `@hello` from the
    // `@hello` cell. Yeah. My head hurts a little too.
    assertEquals(
        new BuildTargetSourcePath(
            BuildTarget.of(
                UnflavoredBuildTarget.of(
                    helloRoot,
                    Optional.of("hello"),
                    "//",
                    "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coercingAbsolutePathThrows() throws CoerceFailedException, IOException {
    Path path = MorePathsForTests.rootRelativePath("hello.a");
    projectFilesystem.touch(path);

    exception.expect(CoerceFailedException.class);
    exception.expectMessage(
        "SourcePath cannot contain an absolute path");

    sourcePathTypeCoercer.coerce(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        path.toString());
  }
}
