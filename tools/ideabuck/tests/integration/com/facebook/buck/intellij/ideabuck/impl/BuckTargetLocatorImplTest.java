/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.impl;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.google.common.collect.Lists;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestCase;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Integration test for {@link BuckTargetLocatorImpl}. */
public class BuckTargetLocatorImplTest extends PlatformTestCase {

  private Project project;
  private BuckCellSettingsProvider buckCellSettingsProvider;
  private BuckCell defaultCell;
  private BuckTargetLocator buckTargetLocator;
  private VirtualFile tempDir;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    tempDir = getTempDir().createTempVDir();
    project = getProject();
    buckCellSettingsProvider = BuckCellSettingsProvider.getInstance(project);
    defaultCell = setDefaultCell(null, null, null);
    buckTargetLocator = BuckTargetLocator.getInstance(project);
  }

  // Helper methods

  @NotNull
  private <T> T unwrap(Optional<T> optional) {
    T t = optional.orElse(null);
    assertNotNull(t);
    return t;
  }

  private <T> void assertOptionalEquals(T expected, Optional<T> actual) {
    assertEquals(expected, actual.orElse(null));
  }

  public BuckCell createCell(
      @Nullable String name, @Nullable String tempRelativePath, @Nullable String buildfileName) {
    BuckCell cell = new BuckCell();
    if (name != null) {
      cell = cell.withName(name);
    }
    if (tempRelativePath != null) {
      cell = cell.withRoot(Paths.get(tempDir.getPath()).resolve(tempRelativePath).toString());
    }
    if (buildfileName != null) {
      cell = cell.withBuildFileName(buildfileName);
    }
    return cell;
  }

  public BuckCell setDefaultCell(
      @Nullable String name, @Nullable String tempRelativePath, String buildfileName) {
    BuckCell cell = createCell(name, tempRelativePath, buildfileName);
    buckCellSettingsProvider.setCells(Collections.singletonList(cell));
    return cell;
  }

  public BuckCell addCell(String name, String tempRelativePath, String buildfileName) {
    BuckCell cell = createCell(name, tempRelativePath, buildfileName);
    List<BuckCell> cells = Lists.newArrayList(buckCellSettingsProvider.getCells());
    cells.add(cell);
    buckCellSettingsProvider.setCells(cells);
    return cell;
  }

  private Path createFile(BuckCell cell, String cellRelativePath) {
    try {
      String basePath = project.getBasePath();
      String root = cell.getRoot().replace("$PROJECT_DIR$", basePath);
      Path path = Paths.get(root).resolve(cellRelativePath);
      path.getParent().toFile().mkdirs();
      try (FileWriter writer = new FileWriter(path.toFile())) {
        writer.write("Test:" + getName());
      }
      return path;
    } catch (IOException e) {
      fail("Failed to create test file: " + e.getMessage());
      throw new RuntimeException("Failed");
    }
  }

  private Path createFileInDefaultCell(String defaultCellRelativePath) {
    return createFile(defaultCell, defaultCellRelativePath);
  }

  private VirtualFile asVirtualFile(@Nullable Path expectedPath) {
    if (expectedPath == null) {
      return null;
    }
    return project.getBaseDir().getFileSystem().refreshAndFindFileByPath(expectedPath.toString());
  }

  // Actual tests start here

  // Test findPathForTarget and findVirtualFileForTarget together

  private void checkFindTarget(String targetString, Path expected) {
    BuckTarget target = unwrap(BuckTarget.parse(targetString));
    assertOptionalEquals(expected, buckTargetLocator.findPathForTarget(target));
    assertOptionalEquals(
        asVirtualFile(expected), buckTargetLocator.findVirtualFileForTarget(target));
  }

  public void testFindTargetInDefaultCell() {
    checkFindTarget("//foo/bar:baz", createFileInDefaultCell("foo/bar/BUCK"));
  }

  public void testFindTargetInDefaultCellWhenBuildFileNameSpecified() {
    defaultCell = setDefaultCell(null, null, "BUILD");
    checkFindTarget("//foo/bar:baz", createFileInDefaultCell("foo/bar/BUILD"));
  }

  public void testFindTargetInNamedCell() throws IOException {
    BuckCell cell = addCell("foo", "x", "FOOBUCK");
    checkFindTarget("foo//bar/baz:qux", createFile(cell, "bar/baz/FOOBUCK"));
  }

  // Test findPathForExtensionFile and findVirtualFileForExtensionFile together

  private void checkFindExtensionFile(String targetString, Path expected) {
    BuckTarget target = unwrap(BuckTarget.parse(targetString));
    assertOptionalEquals(expected, buckTargetLocator.findPathForExtensionFile(target));
    assertOptionalEquals(
        asVirtualFile(expected), buckTargetLocator.findVirtualFileForExtensionFile(target));
  }

  public void testFindExtensionFileInDefaultCell() throws IOException {
    checkFindExtensionFile("//foo/bar:baz.bzl", createFileInDefaultCell("foo/bar/baz.bzl"));
  }

  public void testFindExtensionFileInNamedCell() throws IOException {
    BuckCell cell = addCell("foo", "x", null);
    checkFindExtensionFile("foo//bar:baz.bzl", createFile(cell, "bar/baz.bzl"));
  }

  // Test findPathForTargetPattern and findVirtualFileForTargetPattern together

  public void checkFindForTargetPattern(String patternString, Path expected) {
    BuckTargetPattern pattern = unwrap(BuckTargetPattern.parse(patternString));
    assertOptionalEquals(expected, buckTargetLocator.findPathForTargetPattern(pattern));
    assertOptionalEquals(
        asVirtualFile(expected), buckTargetLocator.findVirtualFileForTargetPattern(pattern));
  }

  public void testFindForTargetPatternInDefaultCell() throws IOException {
    Path buildFile = createFileInDefaultCell("foo/bar/BUCK");
    checkFindForTargetPattern("//foo/bar", buildFile);
    checkFindForTargetPattern("//foo/bar:", buildFile);
    checkFindForTargetPattern("//foo/bar:baz", buildFile);
    checkFindForTargetPattern("//foo/bar/...", buildFile.getParent());
  }

  public void testFindForTargetPatternInDefaultCellWithNamedBuildfile() throws IOException {
    defaultCell = setDefaultCell(null, null, "BUILD");
    Path buildFile = createFileInDefaultCell("foo/bar/BUILD");
    checkFindForTargetPattern("//foo/bar", buildFile);
    checkFindForTargetPattern("//foo/bar:", buildFile);
    checkFindForTargetPattern("//foo/bar:baz", buildFile);
    checkFindForTargetPattern("//foo/bar/...", buildFile.getParent());
  }

  public void testFindForTargetPatternInNamedCell() throws IOException {
    BuckCell cell = addCell("foo", "x", "FOOBUCK");

    Path buildFile = createFile(cell, "bar/baz/FOOBUCK");
    checkFindForTargetPattern("foo//bar/baz", buildFile);
    checkFindForTargetPattern("foo//bar/baz:", buildFile);
    checkFindForTargetPattern("foo//bar/baz:qux", buildFile);
    checkFindForTargetPattern("foo//bar/baz/...", buildFile.getParent());
  }

  // Test findTargetPatternForPath and findTargetPatternForVirtualFile together

  public void checkFindTargetPatternFrom(Path path, String expectedPatternString) {
    BuckTargetPattern expectedPattern = unwrap(BuckTargetPattern.parse(expectedPatternString));
    assertOptionalEquals(expectedPattern, buckTargetLocator.findTargetPatternForPath(path));
    assertOptionalEquals(
        expectedPattern, buckTargetLocator.findTargetPatternForVirtualFile(asVirtualFile(path)));
  }

  public void testFindTargetPatternFromFileInDefaultCell() {
    Path buildFileOne = createFileInDefaultCell("one/BUCK");
    Path buildFileTwo = createFileInDefaultCell("one/two/BUCK");

    checkFindTargetPatternFrom(buildFileOne, "//one:");
    checkFindTargetPatternFrom(buildFileTwo, "//one/two:");

    checkFindTargetPatternFrom(createFileInDefaultCell("a.bzl"), "//:a.bzl");
    checkFindTargetPatternFrom(createFileInDefaultCell("one/a.bzl"), "//one:a.bzl");
    checkFindTargetPatternFrom(createFileInDefaultCell("one/two/a.bzl"), "//one/two:a.bzl");
    checkFindTargetPatternFrom(
        createFileInDefaultCell("one/two/three/a.bzl"), "//one/two:three/a.bzl");
  }

  public void testFindTargetPatternFromDirectoryInDefaultCell() {
    Path rootDir = createFileInDefaultCell(".buckconfig").getParent();
    Path level1Dir = createFileInDefaultCell("one/BUCK").getParent();
    Path level2Dir = createFileInDefaultCell("one/two/BUCK").getParent();
    Path level3Dir = createFileInDefaultCell("one/two/three/x").getParent();
    Path level4Dir = createFileInDefaultCell("one/two/three/four/y").getParent();

    checkFindTargetPatternFrom(rootDir, "//");
    checkFindTargetPatternFrom(level1Dir, "//one/");
    checkFindTargetPatternFrom(level2Dir, "//one/two/");
    checkFindTargetPatternFrom(level3Dir, "//one/two:three/");
    checkFindTargetPatternFrom(level4Dir, "//one/two:three/four/");
  }

  public void testFindTargetPatternFromFileInDefaultCellWithNamedBuildfile() {
    defaultCell = setDefaultCell(null, null, "BUILD");

    Path buildFileOne = createFileInDefaultCell("one/BUILD");
    Path buildFileTwo = createFileInDefaultCell("one/two/BUILD");

    checkFindTargetPatternFrom(buildFileOne, "//one:");
    checkFindTargetPatternFrom(buildFileTwo, "//one/two:");
  }

  public void testFindTargetPatternFromDirectoryInDefaultCellWithNamedBuildFile() {
    defaultCell = setDefaultCell(null, null, "BUILD");

    Path rootDir = createFileInDefaultCell(".buckconfig").getParent();
    Path level1Dir = createFileInDefaultCell("one/BUILD").getParent();
    Path level2Dir = createFileInDefaultCell("one/two/BUILD").getParent();
    Path level3Dir = createFileInDefaultCell("one/two/three/x").getParent();
    Path level4Dir = createFileInDefaultCell("one/two/three/four/y").getParent();

    checkFindTargetPatternFrom(rootDir, "//");
    checkFindTargetPatternFrom(level1Dir, "//one/");
    checkFindTargetPatternFrom(level2Dir, "//one/two/");
    checkFindTargetPatternFrom(level3Dir, "//one/two:three/");
    checkFindTargetPatternFrom(level4Dir, "//one/two:three/four/");
  }

  public void testFindTargetPatternFromFileInNamedCell() {
    BuckCell cell = addCell("foo", "x", "FOO");

    Path buildFileOne = createFile(cell, "one/FOO");
    Path buildFileTwo = createFile(cell, "one/two/FOO");

    checkFindTargetPatternFrom(buildFileOne, "foo//one:");
    checkFindTargetPatternFrom(buildFileTwo, "foo//one/two:");

    checkFindTargetPatternFrom(createFile(cell, "a.bzl"), "foo//:a.bzl");
    checkFindTargetPatternFrom(createFile(cell, "one/a.bzl"), "foo//one:a.bzl");
    checkFindTargetPatternFrom(createFile(cell, "one/two/a.bzl"), "foo//one/two:a.bzl");
    checkFindTargetPatternFrom(createFile(cell, "one/two/three/a.bzl"), "foo//one/two:three/a.bzl");
  }

  public void testFindTargetPatternFromDirectoryInNamedCell() {
    BuckCell cell = addCell("foo", "x", "FOO");

    Path rootDir = createFile(cell, ".buckconfig").getParent();
    Path level1Dir = createFile(cell, "one/FOO").getParent();
    Path level2Dir = createFile(cell, "one/two/FOO").getParent();
    Path level3Dir = createFile(cell, "one/two/three/x").getParent();
    Path level4Dir = createFile(cell, "one/two/three/four/y").getParent();

    checkFindTargetPatternFrom(rootDir, "foo//");
    checkFindTargetPatternFrom(level1Dir, "foo//one/");
    checkFindTargetPatternFrom(level2Dir, "foo//one/two/");
    checkFindTargetPatternFrom(level3Dir, "foo//one/two:three/");
    checkFindTargetPatternFrom(level4Dir, "foo//one/two:three/four/");
  }

  public void testWhenDirectoriesAreNamedTheSameAsBuildfileName() {
    /*
     * For example, the BUCK repo has a directory 'src/java/com/facebook/buck', so on
     * case-insensitive filesystems, need to be extra-sure to check whether a BUCK
     * in the path is a file or a directory!
     */

    BuckCell cell = addCell("foo", "foo", "FOO");
    Path buildFileOne = createFile(cell, "one/FOO");
    Path buildFileTwo = createFile(cell, "two/FOO/FOO");
    Path buildFileThree = createFile(cell, "three/FOO/FOO/FOO");

    // Check files
    checkFindTargetPatternFrom(buildFileOne, "foo//one:");
    checkFindTargetPatternFrom(buildFileTwo, "foo//two/FOO:");
    checkFindTargetPatternFrom(buildFileThree, "foo//three/FOO/FOO:");

    // Check directories with build files
    checkFindTargetPatternFrom(buildFileOne.getParent(), "foo//one/");
    checkFindTargetPatternFrom(buildFileTwo.getParent(), "foo//two/FOO/");
    checkFindTargetPatternFrom(buildFileThree.getParent(), "foo//three/FOO/FOO/");

    // Check directories without build files
    checkFindTargetPatternFrom(buildFileTwo.getParent().getParent(), "foo//:two/");
    checkFindTargetPatternFrom(buildFileThree.getParent().getParent(), "foo//:three/FOO/");

    // Check that a directory named the same as the buildfile.name doesn't fool us
    checkFindTargetPatternFrom(createFile(cell, "one/x/FOO/y"), "foo//one:x/FOO/y");
  }

  // Check findBuckFileForVirtualFile and findBuckFileForPath together
  private void checkFindBuckFile(@Nullable Path expectedBuckFilePath, Path sourceFilePath) {
    assertOptionalEquals(
        expectedBuckFilePath, buckTargetLocator.findBuckFileForPath(sourceFilePath));
    assertOptionalEquals(
        asVirtualFile(expectedBuckFilePath),
        buckTargetLocator.findBuckFileForVirtualFile(asVirtualFile(sourceFilePath)));
  }

  public void testFindBuckFileInDefaultCell() {
    Path level0BazelFile = createFileInDefaultCell("defs.bzl");
    Path level1BuildFile = createFileInDefaultCell("a/BUCK");
    Path level2BazelFile = createFileInDefaultCell("a/b/defs.bzl");
    Path level3BuildFile = createFileInDefaultCell("a/b/c/BUCK");
    Path level4BazelFile = createFileInDefaultCell("a/b/c/d/defs.bzl");

    checkFindBuckFile(null, level0BazelFile);
    checkFindBuckFile(level1BuildFile, level1BuildFile);
    checkFindBuckFile(level1BuildFile, level2BazelFile);
    checkFindBuckFile(level3BuildFile, level3BuildFile);
    checkFindBuckFile(level3BuildFile, level4BazelFile);
  }

  public void testFindBuckFileInNamedCell() {
    BuckCell cell = addCell("foo", "foo", "FOO");
    Path level0BazelFile = createFile(cell, "defs.bzl");
    Path level1BuildFile = createFile(cell, "a/FOO");
    Path level2BazelFile = createFile(cell, "a/b/defs.bzl");
    Path level3BuildFile = createFile(cell, "a/b/c/FOO");
    Path level4BazelFile = createFile(cell, "a/b/c/d/defs.bzl");

    checkFindBuckFile(null, level0BazelFile);
    checkFindBuckFile(level1BuildFile, level1BuildFile);
    checkFindBuckFile(level1BuildFile, level2BazelFile);
    checkFindBuckFile(level3BuildFile, level3BuildFile);
    checkFindBuckFile(level3BuildFile, level4BazelFile);
  }

  /*
   * To ensure consistency, check the permutations of resolve methods
   * (for both VirtualFile/Path, and Targets/Patterns) together
   */
  private void checkResolveFrom(
      String expectedPatternString, Path sourcePath, String sourcePatternString) {
    BuckTargetPattern expectedPattern = unwrap(BuckTargetPattern.parse(expectedPatternString));
    BuckTargetPattern sourcePattern = unwrap(BuckTargetPattern.parse(sourcePatternString));
    assertOptionalEquals(expectedPattern, buckTargetLocator.resolve(sourcePath, sourcePattern));
    assertOptionalEquals(
        expectedPattern, buckTargetLocator.resolve(asVirtualFile(sourcePath), sourcePattern));

    expectedPattern
        .asBuckTarget()
        .ifPresent(
            expectedTarget -> {
              sourcePattern
                  .asBuckTarget()
                  .ifPresent(
                      sourceTarget -> {
                        assertOptionalEquals(
                            expectedTarget, buckTargetLocator.resolve(sourcePath, sourceTarget));
                        assertOptionalEquals(
                            expectedTarget,
                            buckTargetLocator.resolve(asVirtualFile(sourcePath), sourceTarget));
                      });
            });
  }

  public void testResolveInDefaultCell() {
    Path level0BazelFile = createFileInDefaultCell("defs.bzl");
    Path level1BuildFile = createFileInDefaultCell("a/BUCK");
    Path level2BazelFile = createFileInDefaultCell("a/b/defs.bzl");
    Path level3BuildFile = createFileInDefaultCell("a/b/c/BUCK");
    Path level4BazelFile = createFileInDefaultCell("a/b/c/d/defs.bzl");

    checkResolveFrom("//:defs.bzl", level0BazelFile, ":defs.bzl");
    checkResolveFrom("//a:defs.bzl", level1BuildFile, ":defs.bzl");
    checkResolveFrom("//a/b:defs.bzl", level2BazelFile, ":defs.bzl");
    checkResolveFrom("//a/b/c:defs.bzl", level3BuildFile, ":defs.bzl");
    checkResolveFrom("//a/b/c/d:defs.bzl", level4BazelFile, ":defs.bzl");

    checkResolveFrom("//:a/b/defs.bzl", level0BazelFile, ":a/b/defs.bzl");
    checkResolveFrom("//a:b/defs.bzl", level1BuildFile, ":b/defs.bzl");
    checkResolveFrom("//a/b:defs.bzl", level2BazelFile, ":defs.bzl");
  }

  public void testResolveInNamedCell() {
    BuckCell cell = addCell("foo", "foo", "FOO");
    Path level0BazelFile = createFile(cell, "defs.bzl");
    Path level1BuildFile = createFile(cell, "a/FOO");
    Path level2BazelFile = createFile(cell, "a/b/defs.bzl");
    Path level3BuildFile = createFile(cell, "a/b/c/FOO");
    Path level4BazelFile = createFile(cell, "a/b/c/d/defs.bzl");

    checkResolveFrom("foo//:defs.bzl", level0BazelFile, ":defs.bzl");
    checkResolveFrom("foo//a:defs.bzl", level1BuildFile, ":defs.bzl");
    checkResolveFrom("foo//a/b:defs.bzl", level2BazelFile, ":defs.bzl");
    checkResolveFrom("foo//a/b/c:defs.bzl", level3BuildFile, ":defs.bzl");
    checkResolveFrom("foo//a/b/c/d:defs.bzl", level4BazelFile, ":defs.bzl");

    checkResolveFrom("foo//:a/b/defs.bzl", level0BazelFile, ":a/b/defs.bzl");
    checkResolveFrom("foo//a:b/defs.bzl", level1BuildFile, ":b/defs.bzl");
    checkResolveFrom("foo//a/b:defs.bzl", level2BazelFile, ":defs.bzl");
  }
}
