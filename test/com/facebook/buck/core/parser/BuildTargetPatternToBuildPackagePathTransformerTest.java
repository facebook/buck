/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern.Kind;
import com.facebook.buck.core.parser.buildtargetpattern.ImmutableBuildTargetPattern;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildTargetPatternToBuildPackagePathTransformerTest {

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @SuppressWarnings("unused")
  private Object getSinglePathParams() {
    return new Object[] {
      new Object[] {Kind.SINGLE, "target"},
      new Object[] {Kind.PACKAGE, ""}
    };
  }

  @Test
  @Parameters(method = "getSinglePathParams")
  public void canDiscoverSinglePath(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir1/dir2"));
    filesystem.createNewFile(Paths.get("dir1/dir2/BUCK"));
    filesystem.createNewFile(Paths.get("dir1/dir2/file"));
    filesystem.mkdirs(Paths.get("dir1/dir2/dir3"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir1/dir2", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir1/dir2")), paths.getPackageRoots());
  }

  @Test
  public void canDiscoverRecursivePaths()
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir1"));
    filesystem.createNewFile(Paths.get("dir1/file"));
    filesystem.createNewFile(Paths.get("dir1/BUCK"));
    filesystem.mkdirs(Paths.get("dir1/dir2"));
    filesystem.mkdirs(Paths.get("dir1/dir2/dir3"));
    filesystem.createNewFile(Paths.get("dir1/dir2/dir3/BUCK"));

    BuildPackagePaths paths = transform("BUCK", key("", Kind.RECURSIVE, "dir1", ""));

    assertEquals(
        ImmutableSortedSet.of(Paths.get("dir1"), Paths.get("dir1/dir2/dir3")),
        paths.getPackageRoots());
  }

  @Nonnull
  private static BuildTargetPatternToBuildPackagePathKey key(
      String cell, Kind kind, String basePath, String targetName) {
    return ImmutableBuildTargetPatternToBuildPackagePathKey.of(
        ImmutableBuildTargetPattern.of(cell, kind, Paths.get(basePath), targetName));
  }

  private BuildPackagePaths transform(
      String buildFileName, BuildTargetPatternToBuildPackagePathKey key)
      throws ExecutionException, InterruptedException {
    int estimatedNumOps = 0;
    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(
                new GraphComputationStage<>(
                    BuildTargetPatternToBuildPackagePathTransformer.of(buildFileName)),
                new GraphComputationStage<>(DirectoryListComputation.of(filesystem.asView())),
                new GraphComputationStage<>(FileTreeComputation.of())),
            estimatedNumOps,
            DefaultDepsAwareExecutor.of(1));
    return engine.compute(key).get();
  }
}
