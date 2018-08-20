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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourcePathTypeCoercerTest {
  private FakeProjectFilesystem projectFilesystem;
  private CellPathResolver cellRoots;
  private final Path pathRelativeToProjectRoot = Paths.get("");
  private final SourcePathTypeCoercer sourcePathTypeCoercer =
      new SourcePathTypeCoercer(
          new BuildTargetTypeCoercer(),
          new PathTypeCoercer(PathTypeCoercer.PathExistenceVerificationMode.VERIFY));

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

    SourcePath sourcePath =
        sourcePathTypeCoercer.coerce(cellRoots, projectFilesystem, pathRelativeToProjectRoot, path);

    assertEquals(PathSourcePath.of(projectFilesystem, Paths.get(path)), sourcePath);
  }

  @Test
  public void coerceAbsoluteBuildTarget() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerce(
            cellRoots, projectFilesystem, pathRelativeToProjectRoot, "//:hello");

    assertEquals(
        DefaultBuildTargetSourcePath.of(
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    projectFilesystem.getRootPath(), Optional.empty(), "//", "hello"),
                ImmutableSortedSet.of())),
        sourcePath);
  }

  @Test
  public void coerceRelativeBuildTarget() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerce(
            cellRoots, projectFilesystem, pathRelativeToProjectRoot, ":hello");

    assertEquals(
        DefaultBuildTargetSourcePath.of(
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    projectFilesystem.getRootPath(), Optional.empty(), "//", "hello"),
                ImmutableSortedSet.of())),
        sourcePath);
  }

  @Test
  public void coerceCrossRepoBuildTarget() throws CoerceFailedException {
    Path helloRoot = Paths.get("/opt/src/hello");
    cellRoots =
        DefaultCellPathResolver.of(
            projectFilesystem.getRootPath(), ImmutableMap.of("hello", helloRoot));

    SourcePath sourcePath =
        sourcePathTypeCoercer.coerce(
            cellRoots, projectFilesystem, pathRelativeToProjectRoot, "hello//:hello");

    // Note that the important thing is that the root of the target has been set to `helloRoot` so
    // the cell name should be absent (otherwise, we'd look for a cell named `@hello` from the
    // `@hello` cell. Yeah. My head hurts a little too.
    assertEquals(
        DefaultBuildTargetSourcePath.of(
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(helloRoot, Optional.of("hello"), "//", "hello"),
                ImmutableSortedSet.of())),
        sourcePath);
  }

  @Test
  public void coercingAbsolutePathThrows() throws CoerceFailedException, IOException {
    Path path = MorePathsForTests.rootRelativePath("hello.a");
    projectFilesystem.touch(path);

    exception.expect(CoerceFailedException.class);
    exception.expectMessage("SourcePath cannot contain an absolute path");

    sourcePathTypeCoercer.coerce(
        cellRoots, projectFilesystem, pathRelativeToProjectRoot, path.toString());
  }
}
