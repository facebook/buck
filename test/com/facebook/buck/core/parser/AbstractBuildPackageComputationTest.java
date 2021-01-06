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

package com.facebook.buck.core.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern.Kind;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.AssumePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import junitparams.Parameters;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public abstract class AbstractBuildPackageComputationTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  protected ProjectFilesystem filesystem;

  protected abstract ImmutableList<GraphComputationStage<?, ?>> getComputationStages(
      String buildFileName);

  /** Should a file called "Buck" be treated as a BUCK build file? */
  protected boolean isBuildFileCaseSensitive() {
    return true;
  }

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

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1/dir2", targetName));

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

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), Kind.RECURSIVE, "dir1", ""));

    assertEquals(
        ImmutableSortedSet.of(Paths.get("dir1"), Paths.get("dir1/dir2/dir3")),
        paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void canDiscoverRootPath(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.createNewFile(Paths.get("BUCK"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getSinglePathParams")
  public void singlePackageDoesNotMatchChildrenPackages(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir1"));
    filesystem.createNewFile(Paths.get("dir1/BUCK"));
    filesystem.mkdirs(Paths.get("dir1/dir2"));
    filesystem.createNewFile(Paths.get("dir1/dir2/BUCK"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir1")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void returnsNothingIfBuildFileDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir1/dir2"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1/dir2", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void returnsNothingIfNamedDirectoryDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorDoesNotExist(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1"));

    BuildTargetPatternToBuildPackagePathKey key =
        key(CanonicalCellName.rootCell(), kind, "dir1/dir2", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryIsBrokenSymlink(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createSymLink(Paths.get("symlink"), Paths.get("target-does-not-exist"), false);

    BuildTargetPatternToBuildPackagePathKey key =
        key(CanonicalCellName.rootCell(), kind, "symlink", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(
        Matchers.anyOf(
            IsInstanceOf.instanceOf(NotDirectoryException.class),
            IsInstanceOf.instanceOf(NoSuchFileException.class)));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorIsBrokenSymlink(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createSymLink(Paths.get("symlink"), Paths.get("target-does-not-exist"), false);

    BuildTargetPatternToBuildPackagePathKey key =
        key(CanonicalCellName.rootCell(), kind, "symlink/dir", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryIsRegularFile(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createNewFile(Paths.get("file"));

    BuildTargetPatternToBuildPackagePathKey key =
        key(CanonicalCellName.rootCell(), kind, "file", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NotDirectoryException.class));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void throwsIfNamedDirectoryAncestorIsRegularFile(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.createNewFile(Paths.get("file"));

    BuildTargetPatternToBuildPackagePathKey key =
        key(CanonicalCellName.rootCell(), kind, "file/dir", targetName);

    thrown.expect(ExecutionException.class);
    thrown.expectCause(
        Matchers.anyOf(
            IsInstanceOf.instanceOf(NoSuchFileException.class),
            IsInstanceOf.instanceOf(NotDirectoryException.class)));
    transform("BUCK", key);
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void processesSymlinkBuildFiles(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {

    // Watchman throws "GetOverlappedResult() failed for read operation" while trying
    // with symlinks on Windows
    assumeFalse(isWindows());

    filesystem.mkdirs(Paths.get("dir1"));
    filesystem.mkdirs(Paths.get("dir2"));
    filesystem.createSymLink(Paths.get("dir1/BUCK"), Paths.get("dir2/BUCK_SYMLINK"), false);
    filesystem.createNewFile(Paths.get("dir2/BUCK_SYMLINK"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir1")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void ignoresDirectoryBuildFiles(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("dir1/BUCK"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir1", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  public void recursiveSearchIgnoresSymlinksToDirectories()
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("target-dir"));
    filesystem.createNewFile(Paths.get("target-dir/BUCK"));
    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createSymLink(Paths.get("dir/symlink-dir"), Paths.get("../target-dir"), true);

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), Kind.RECURSIVE, "dir", ""));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void followsSymlinksInNamedPath(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    filesystem.mkdirs(Paths.get("target-dir/sub-dir"));
    filesystem.createNewFile(Paths.get("target-dir/sub-dir/BUCK"));
    filesystem.createSymLink(Paths.get("symlink-dir"), Paths.get("target-dir"), true);

    BuildPackagePaths paths =
        transform(
            "BUCK", key(CanonicalCellName.rootCell(), kind, "symlink-dir/sub-dir", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("symlink-dir/sub-dir")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void requiresExactBuildFileCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    assumeTrue(isBuildFileCaseSensitive());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BuCk"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir", targetName));

    assertEquals(ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void allowsInexactBuildFileCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    assumeFalse(isBuildFileCaseSensitive());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BuCk"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "dir", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void allowsInexactPathCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    AssumePath.assumeNamesAreCaseInsensitive(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BUCK"));

    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "DIR", targetName));

    assertEquals(ImmutableSortedSet.of(Paths.get("DIR")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void requiresExactPathCase(Kind kind, String targetName)
      throws ExecutionException, InterruptedException, IOException {
    AssumePath.assumeNamesAreCaseSensitive(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("dir"));
    filesystem.createNewFile(Paths.get("dir/BUCK"));

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(NoSuchFileException.class));
    transform("BUCK", key(CanonicalCellName.rootCell(), kind, "DIR", targetName));
  }

  @Test
  public void findsBuildFilesWithSpecialStarGlobCharacter()
      throws ExecutionException, IOException, InterruptedException {
    AssumePath.assumeStarIsAllowedInNames(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("dir-star"));
    filesystem.createNewFile(Paths.get("dir-star/*UCK"));
    filesystem.mkdirs(Paths.get("dir-luck"));
    filesystem.createNewFile(Paths.get("dir-luck/LUCK"));

    BuildPackagePaths paths;

    // '*' is special in glob patterns.
    paths = transform("*UCK", key(CanonicalCellName.rootCell(), Kind.RECURSIVE, "", ""));
    assertEquals(
        "dir-star/*UCK is the only *UCK build file",
        ImmutableSortedSet.of(Paths.get("dir-star")),
        paths.getPackageRoots());
    paths = transform("*UCK", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-star", ""));
    assertEquals(
        "dir-star/*UCK is a *UCK build file",
        ImmutableSortedSet.of(Paths.get("dir-star")),
        paths.getPackageRoots());
    paths = transform("*UCK", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-luck", ""));
    assertEquals(
        "dir-luck/LUCK is not a *UCK build file", ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  public void findsBuildFilesWithSpecialQuestionGlobCharacter()
      throws ExecutionException, IOException, InterruptedException {
    AssumePath.assumeQuestionIsAllowedInNames(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("dir-question"));
    filesystem.createNewFile(Paths.get("dir-question/?UCK"));
    filesystem.mkdirs(Paths.get("dir-luck"));
    filesystem.createNewFile(Paths.get("dir-luck/LUCK"));

    BuildPackagePaths paths;

    // '?' is special in glob patterns.
    paths = transform("?UCK", key(CanonicalCellName.rootCell(), Kind.RECURSIVE, "", ""));
    assertEquals(
        "dir-star/?UCK is the only ?UCK build file",
        ImmutableSortedSet.of(Paths.get("dir-question")),
        paths.getPackageRoots());
    paths = transform("?UCK", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-question", ""));
    assertEquals(
        "dir-star/?UCK is a ?UCK build file",
        ImmutableSortedSet.of(Paths.get("dir-question")),
        paths.getPackageRoots());
    paths = transform("?UCK", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-luck", ""));
    assertEquals(
        "dir-luck/LUCK is not a ?UCK build file", ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  public void findsBuildFilesWithSpecialBracketGlobCharacters()
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("dir-bracket"));
    filesystem.createNewFile(Paths.get("dir-bracket/[BUCK]"));
    filesystem.mkdirs(Paths.get("dir-b"));
    filesystem.createNewFile(Paths.get("dir-b/B"));

    BuildPackagePaths paths;

    // '[' and ']' are special in glob patterns.
    paths = transform("[BUCK]", key(CanonicalCellName.rootCell(), Kind.RECURSIVE, "", ""));
    assertEquals(
        "dir-bracket/[BUCK] is the only [BUCK] build file",
        ImmutableSortedSet.of(Paths.get("dir-bracket")),
        paths.getPackageRoots());
    paths = transform("[BUCK]", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-bracket", ""));
    assertEquals(
        "dir-bracket/[BUCK] is a [BUCK] build file",
        ImmutableSortedSet.of(Paths.get("dir-bracket")),
        paths.getPackageRoots());
    paths = transform("[BUCK]", key(CanonicalCellName.rootCell(), Kind.PACKAGE, "dir-b", ""));
    assertEquals(
        "dir-b/B is not a [BUCK] build file", ImmutableSortedSet.of(), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void findsInNamedDirectoryWithSpecialStarGlobCharacters(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    AssumePath.assumeStarIsAllowedInNames(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("star-d*r"));
    filesystem.createNewFile(Paths.get("star-d*r/BUCK"));

    // '*' is special in glob patterns.
    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "star-d*r", targetName));
    assertEquals(ImmutableSortedSet.of(Paths.get("star-d*r")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void findsInNamedDirectoryWithSpecialQuestionGlobCharacters(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    AssumePath.assumeQuestionIsAllowedInNames(filesystem.getRootPath().getPath());

    filesystem.mkdirs(Paths.get("question-d?r"));
    filesystem.createNewFile(Paths.get("question-d?r/BUCK"));

    // '?' is special in glob patterns.
    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "question-d?r", targetName));
    assertEquals(ImmutableSortedSet.of(Paths.get("question-d?r")), paths.getPackageRoots());
  }

  @Test
  @Parameters(method = "getAnyPathParams")
  public void findsInNamedDirectoryWithSpecialBracketGlobCharacters(Kind kind, String targetName)
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("bracket-[dir]"));
    filesystem.createNewFile(Paths.get("bracket-[dir]/BUCK"));

    // '[' and ']' are special in glob patterns.
    BuildPackagePaths paths =
        transform("BUCK", key(CanonicalCellName.rootCell(), kind, "bracket-[dir]", targetName));
    assertEquals(ImmutableSortedSet.of(Paths.get("bracket-[dir]")), paths.getPackageRoots());
  }

  @Nonnull
  public static BuildTargetPatternToBuildPackagePathKey key(
      CanonicalCellName cell, Kind kind, String basePath, String targetName) {
    return ImmutableBuildTargetPatternToBuildPackagePathKey.of(
        BuildTargetPattern.of(
            CellRelativePath.of(cell, ForwardRelativePath.of(basePath)), kind, targetName));
  }

  public BuildPackagePaths transform(
      String buildFileName, BuildTargetPatternToBuildPackagePathKey key)
      throws ExecutionException, InterruptedException {
    return transform(key, getComputationStages(buildFileName));
  }

  public BuildPackagePaths transform(
      BuildTargetPatternToBuildPackagePathKey key,
      ImmutableList<GraphComputationStage<?, ?>> stages)
      throws ExecutionException, InterruptedException {
    int estimatedNumOps = 0;
    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            stages, estimatedNumOps, DefaultDepsAwareExecutor.of(1));
    return engine.compute(key).get();
  }

  private static boolean isWindows() {
    return Platform.detect().getType().isWindows();
  }
}
