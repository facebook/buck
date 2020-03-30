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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourcePathTypeCoercerTest {
  private FakeProjectFilesystem projectFilesystem;
  private CellNameResolver cellRoots;
  private final ForwardRelativePath pathRelativeToProjectRoot = ForwardRelativePath.of("");
  private final SourcePathTypeCoercer sourcePathTypeCoercer =
      new SourcePathTypeCoercer(
          new BuildTargetWithOutputsTypeCoercer(
              new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                  new UnconfiguredBuildTargetTypeCoercer(
                      new ParsingUnconfiguredBuildTargetViewFactory()))),
          new PathTypeCoercer());

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    cellRoots = TestCellPathResolver.get(projectFilesystem).getCellNameResolver();
  }

  @Before
  public void setUpCellRoots() {}

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void coercePath() throws CoerceFailedException, IOException {
    String path = "hello.a";
    projectFilesystem.touch(Paths.get(path));

    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            path);

    assertEquals(PathSourcePath.of(projectFilesystem, Paths.get(path)), sourcePath);
  }

  @Test
  public void coercePathRelativeToBasePath() throws CoerceFailedException, IOException {
    projectFilesystem.touch(Paths.get("foo/bar/hello.a"));

    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            ForwardRelativePath.of("foo/bar"),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "hello.a");

    assertEquals(PathSourcePath.of(projectFilesystem, Paths.get("foo/bar/hello.a")), sourcePath);
  }

  @Test
  public void coerceAbsoluteBuildTarget() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//:hello");

    assertEquals(
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:hello")), sourcePath);
  }

  @Test
  public void coerceAbsoluteBuildTargetWithOutputLabel() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//:hello[label]");

    assertEquals(
        DefaultBuildTargetSourcePath.of(
            BuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance("//:hello"), OutputLabel.of("label"))),
        sourcePath);
  }

  @Test
  public void coerceRelativeBuildTarget() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ":hello");

    assertEquals(
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:hello")), sourcePath);
  }

  @Test
  public void coerceRelativeBuildTargetWithOutputLabel() throws CoerceFailedException {
    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ":hello[label]");

    assertEquals(
        DefaultBuildTargetSourcePath.of(
            BuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance("//:hello"), OutputLabel.of("label"))),
        sourcePath);
  }

  @Test
  public void coerceCrossRepoBuildTarget() throws CoerceFailedException {
    AbsPath rootPath = projectFilesystem.getRootPath();
    AbsPath helloRoot = rootPath.resolve("hello");
    cellRoots =
        TestCellPathResolver.create(rootPath, ImmutableMap.of("hello", helloRoot.getPath()))
            .getCellNameResolver();

    SourcePath sourcePath =
        sourcePathTypeCoercer.coerceBoth(
            cellRoots,
            projectFilesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "hello//:hello");

    // Note that the important thing is that the root of the target has been set to `helloRoot` so
    // the cell name should be absent (otherwise, we'd look for a cell named `@hello` from the
    // `@hello` cell. Yeah. My head hurts a little too.
    assertEquals(
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("hello//:hello")),
        sourcePath);
  }

  @Test
  public void coercingAbsolutePathThrows() throws CoerceFailedException, IOException {
    Path path = MorePathsForTests.rootRelativePath("hello.a");
    projectFilesystem.touch(path);

    exception.expect(CoerceFailedException.class);
    exception.expectMessage("Path cannot contain an absolute path");

    sourcePathTypeCoercer.coerceBoth(
        cellRoots,
        projectFilesystem,
        pathRelativeToProjectRoot,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        path.toString());
  }
}
