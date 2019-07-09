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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

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
import com.facebook.buck.testutil.AssumePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.environment.PlatformType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildTargetPatternToBuildPackagePathTransformerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
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

  @SuppressWarnings("unused")
  private Object[][] getAnyPathParams() {
    return new Object[][] {
      new Object[] {Kind.PACKAGE, ""},
      new Object[] {Kind.RECURSIVE, ""},
      new Object[] {Kind.SINGLE, "target"},
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

  @Test
  @Parameters(method = "getAnyPathParams")
  public void returnsNothingIfBuildFileDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir1/dir2"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir1/dir2", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void returnsNothingIfNamedDirectoryDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1"));

    BuildTargetPatternToBuildPackagePathKey key = key("", kind, "dir1/dir2", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryIsBrokenSymlink(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createSymLink(Paths.get("symlink"), Paths.get("target-does-not-exist"), false);

    BuildTargetPatternToBuildPackagePathKey key = key("", kind, "symlink", targetName);

    thrown.expect(ExecutionException.class);
    if (isWindows()) {
      thrown.expectCause(IsInstanceOf.instanceOf(NotDirectoryException.class));
    } else {
      thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    }
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorIsBrokenSymlink(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createSymLink(Paths.get("symlink"), Paths.get("target-does-not-exist"), false);

    BuildTargetPatternToBuildPackagePathKey key = key("", kind, "symlink/dir", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryIsRegularFile(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createNewFile(Paths.get("file"));

    BuildTargetPatternToBuildPackagePathKey key = key("", kind, "file", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NotDirectoryException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorIsRegularFile(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createNewFile(Paths.get("file"));

    BuildTargetPatternToBuildPackagePathKey key = key("", kind, "file/dir", targetName);

    thrown.expect(ExecutionException.class);
    if (isWindows()) {
      thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    } else {
      thrown.expectCause(IsInstanceOf.instanceOf(NotDirectoryException.class));
    }
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void ignoresSymlinkBuildFiles(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1"));
    filesystem.createSymLink(Paths.get("dir1/BUCK"), Paths.get("dir1/not-a-build-file"), false);
    filesystem.createNewFile(Paths.get("dir1/not-a-build-file"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void ignoresDirectoryBuildFiles(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1/BUCK"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  public void recursiveSearchIgnoresSymlinksToDirectories()
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("target-dir"));
    filesystem.createNewFile(Paths.get("target-dir/BUCK"));
    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createSymLink(Paths.get("dir/symlink-dir"), Paths.get("../target-dir"), true);

    BuildPackagePaths paths = transform("BUCK", key("", Kind.RECURSIVE, "dir", ""));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void followsSymlinksInNamedPath(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("target-dir/sub-dir"));
    filesystem.createNewFile(Paths.get("target-dir/sub-dir/BUCK"));
    filesystem.createSymLink(Paths.get("symlink-dir"), Paths.get("target-dir"), true);

    BuildPackagePaths paths = transform("BUCK", key("", kind, "symlink-dir/sub-dir", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("symlink-dir/sub-dir")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void requiresExactBuildFileCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    // TODO: Figure out why Windows behaves differently from Linux and macOS.
    assumeThat(Platform.detect().getType(), not(equalTo(PlatformType.WINDOWS)));

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BuCk"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void allowsInexactBuildFileCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    // TODO: Figure out why Windows behaves differently from Linux and macOS.
    assumeThat(Platform.detect().getType(), equalTo(PlatformType.WINDOWS));

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BuCk"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "dir", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void allowsInexactPathCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    AssumePath.assumeNamesAreCaseInsensitive(filesystem.getRootPath());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BUCK"));

    BuildPackagePaths paths = transform("BUCK", key("", kind, "DIR", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("DIR")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void requiresExactPathCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    AssumePath.assumeNamesAreCaseSensitive(filesystem.getRootPath());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BUCK"));

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key("", kind, "DIR", targetName));
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

  private static boolean isWindows() {
    return Platform.detect().getType().isWindows();
  }
}
