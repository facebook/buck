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

package com.facebook.buck.core.rules.common;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class SourcePathSupportTest {

  private ProjectFilesystem projectFilesystem;

  private final BuildTarget fooBar = BuildTargetFactory.newInstance("//foo:bar");
  private final BuildTarget bazBar = BuildTargetFactory.newInstance("//baz:bar");
  private final BuildTarget fooQux = BuildTargetFactory.newInstance("//foo:qux");

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
  }

  private SourcePath makePathSourcePath(String first, String... more) {
    return PathSourcePath.of(projectFilesystem, Paths.get(first, more));
  }

  private ImmutableList<SourcePath> makeSourcePaths() {
    ImmutableList.Builder<SourcePath> sourcePathsBuilder = ImmutableList.builder();

    // -- PathSourcePath

    sourcePathsBuilder.add(makePathSourcePath("dir1", "bar1.c"));
    sourcePathsBuilder.add(makePathSourcePath("dir1", "bar2.c"));
    sourcePathsBuilder.add(makePathSourcePath("dir1", "foo.c"));
    sourcePathsBuilder.add(makePathSourcePath("dir2", "foo.c"));

    // -- ArchiveMemberSourcePath

    SourcePath archive1Path = makePathSourcePath("dir1", "object.o");
    SourcePath archive2Path = makePathSourcePath("dir2", "object.o");

    sourcePathsBuilder.add(ArchiveMemberSourcePath.of(archive1Path, Paths.get("symbol1")));
    sourcePathsBuilder.add(ArchiveMemberSourcePath.of(archive1Path, Paths.get("symbol2")));
    sourcePathsBuilder.add(ArchiveMemberSourcePath.of(archive2Path, Paths.get("symbol1")));

    // -- DefaultBuildTargetSourcePath

    OutputLabel defaultLabel = OutputLabel.defaultLabel();
    OutputLabel waldoLabel = OutputLabel.of("waldo");

    sourcePathsBuilder.add(DefaultBuildTargetSourcePath.of(bazBar));
    sourcePathsBuilder.add(DefaultBuildTargetSourcePath.of(fooQux));
    sourcePathsBuilder.add(
        DefaultBuildTargetSourcePath.of(BuildTargetWithOutputs.of(fooBar, defaultLabel)));
    sourcePathsBuilder.add(
        DefaultBuildTargetSourcePath.of(BuildTargetWithOutputs.of(fooBar, waldoLabel)));
    sourcePathsBuilder.add(
        DefaultBuildTargetSourcePath.of(BuildTargetWithOutputs.of(bazBar, waldoLabel)));
    sourcePathsBuilder.add(
        DefaultBuildTargetSourcePath.of(BuildTargetWithOutputs.of(fooQux, waldoLabel)));

    // -- ForwardingBuildTargetSourcePath

    SourcePath waldo = makePathSourcePath("waldo");
    SourcePath fred = makePathSourcePath("fred");

    sourcePathsBuilder.add(ForwardingBuildTargetSourcePath.of(fooBar, waldo));
    sourcePathsBuilder.add(ForwardingBuildTargetSourcePath.of(fooBar, fred));
    sourcePathsBuilder.add(ForwardingBuildTargetSourcePath.of(fooQux, waldo));
    sourcePathsBuilder.add(ForwardingBuildTargetSourcePath.of(bazBar, waldo));

    // -- ExplicitBuildTargetSourcePath

    Path waldoPath = Paths.get("waldo");
    Path fredPath = Paths.get("fred");

    sourcePathsBuilder.add(ExplicitBuildTargetSourcePath.of(fooBar, waldoPath));
    sourcePathsBuilder.add(ExplicitBuildTargetSourcePath.of(fooBar, fredPath));
    sourcePathsBuilder.add(ExplicitBuildTargetSourcePath.of(fooQux, waldoPath));
    sourcePathsBuilder.add(ExplicitBuildTargetSourcePath.of(bazBar, waldoPath));

    return sourcePathsBuilder.build();
  }

  @Test
  public void testSourcePathBuildTargetsDeterminism() {
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//A:B");
    ImmutableList<SourcePath> sourcePaths1 = makeSourcePaths();
    ImmutableList<SourcePath> sourcePaths2 = makeSourcePaths();

    // Verify determinism by creating different but equal instances of SourcePath and verifying
    // equality of the generated maps
    ImmutableBiMap<SourcePath, BuildTarget> buildTargetsMap1 =
        SourcePathSupport.generateAndCheckUniquenessOfBuildTargetsForSourcePaths(
            sourcePaths1, baseTarget, "hash-");
    ImmutableBiMap<SourcePath, BuildTarget> buildTargetsMap2 =
        SourcePathSupport.generateAndCheckUniquenessOfBuildTargetsForSourcePaths(
            sourcePaths2, baseTarget, "hash-");

    assertThat(buildTargetsMap1, equalTo(buildTargetsMap2));
  }

  @Test
  public void testSourcePathBuildTargetsUniqueness() {
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//A:B");
    ImmutableList<SourcePath> sourcePaths = makeSourcePaths();
    // Verify uniqueness by creating a bi-map
    SourcePathSupport.generateAndCheckUniquenessOfBuildTargetsForSourcePaths(
        sourcePaths, baseTarget, "hash-");
  }
}
