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
import org.gradle.internal.impldep.com.google.common.collect.Lists;
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

  private VirtualFile asVirtualFile(Path expectedPath) {
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
  }

  public void testFindTargetPatternFromFileInDefaultCellWithNamedBuildfile() {
    defaultCell = setDefaultCell(null, null, "BUILD");

    Path buildFileOne = createFileInDefaultCell("one/BUILD");
    Path buildFileTwo = createFileInDefaultCell("one/two/BUILD");

    checkFindTargetPatternFrom(buildFileOne, "//one:");
    checkFindTargetPatternFrom(buildFileTwo, "//one/two:");
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
  }
}
