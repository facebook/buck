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
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestCase;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Integration test for {@link BuckTargetLocatorImpl}. */
public class BuckTargetLocatorImplTest extends PlatformTestCase {

  private Project project;
  private BuckProjectSettingsProvider buckProjectSettingsProvider;
  private BuckTargetLocator buckTargetLocator;
  private VirtualFile tempDir;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    tempDir = getTempDir().createTempVDir();
    project = getProject();
    buckProjectSettingsProvider = BuckProjectSettingsProvider.getInstance(project);
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
      @Nullable String name, @Nullable String projectRelativePath, @Nullable String buildfileName) {
    BuckCell cell = new BuckCell();
    if (name != null) {
      cell = cell.withName(name);
    }
    if (projectRelativePath != null) {
      cell = cell.withRoot(Paths.get(tempDir.getPath()).resolve(projectRelativePath).toString());
    }
    if (buildfileName != null) {
      cell = cell.withBuildFileName(buildfileName);
    }
    return cell;
  }

  public void setUpCell(
      @Nullable String name, @Nullable String projectRelativePath, String buildfileName) {
    BuckCell cell = createCell(name, projectRelativePath, buildfileName);
    buckProjectSettingsProvider.setCells(Collections.singletonList(cell));
  }

  public void addCell(String name, String projectRelativePath, String buildfileName) {
    BuckCell cell = createCell(name, projectRelativePath, buildfileName);
    List<BuckCell> cells = new ArrayList<>();
    buckProjectSettingsProvider.getCells().forEach(cells::add);
    cells.add(cell);
    buckProjectSettingsProvider.setCells(cells);
  }

  private Path createFile(String projectRelativePath) {
    try {
      Path path = Paths.get(tempDir.getPath()).resolve(projectRelativePath);
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
    setUpCell(null, null, "BUILD");
    checkFindTarget("//foo/bar:baz", createFile("foo/bar/BUILD"));
  }

  public void testFindTargetInNamedCell() throws IOException {
    setUpCell("foo", "x", "FOOBUCK");
    checkFindTarget("foo//bar/baz:qux", createFile("x/bar/baz/FOOBUCK"));
  }

  // Test findPathForExtensionFile and findVirtualFileForExtensionFile together

  private void checkFindExtensionFile(String targetString, Path expected) {
    BuckTarget target = unwrap(BuckTarget.parse(targetString));
    assertOptionalEquals(expected, buckTargetLocator.findPathForExtensionFile(target));
    assertOptionalEquals(
        asVirtualFile(expected), buckTargetLocator.findVirtualFileForExtensionFile(target));
  }

  public void testFindExtensionFileInDefaultCell() throws IOException {
    setUpCell(null, null, "BUILD");
    checkFindExtensionFile("//foo/bar:baz.bzl", createFile("foo/bar/baz.bzl"));
  }

  public void testFindExtensionFileInNamedCell() throws IOException {
    setUpCell("foo", "x", null);
    checkFindExtensionFile("foo//bar:baz.bzl", createFile("x/bar/baz.bzl"));
  }

  // Test findPathForTargetPattern and findVirtualFileForTargetPattern together

  public void checkFindForTargetPattern(String patternString, Path expected) {
    BuckTargetPattern pattern = unwrap(BuckTargetPattern.parse(patternString));
    assertOptionalEquals(expected, buckTargetLocator.findPathForTargetPattern(pattern));
    assertOptionalEquals(
        asVirtualFile(expected), buckTargetLocator.findVirtualFileForTargetPattern(pattern));
  }

  public void testFindForTargetPatternInDefaultCell() throws IOException {
    setUpCell(null, null, "BUILD");
    Path buildFile = createFile("foo/bar/BUILD");
    checkFindForTargetPattern("//foo/bar", buildFile);
    checkFindForTargetPattern("//foo/bar:", buildFile);
    checkFindForTargetPattern("//foo/bar:baz", buildFile);
    checkFindForTargetPattern("//foo/bar/...", buildFile.getParent());
  }

  public void testFindForTargetPatternInNamedCell() throws IOException {
    setUpCell("foo", "x", "FOOBUCK");
    Path buildFile = createFile("x/bar/baz/FOOBUCK");
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
    setUpCell(null, null, "FOO");

    Path buildFileOne = createFile("one/FOO");
    Path buildFileTwo = createFile("one/two/FOO");

    checkFindTargetPatternFrom(buildFileOne, "//one:");
    checkFindTargetPatternFrom(buildFileTwo, "//one/two:");

    checkFindTargetPatternFrom(createFile("a.bzl"), "//:a.bzl");
    checkFindTargetPatternFrom(createFile("one/a.bzl"), "//one:a.bzl");
    checkFindTargetPatternFrom(createFile("one/two/a.bzl"), "//one/two:a.bzl");
  }

  public void testFindTargetPatternForVirtualFile() {
    setUpCell("foo", "x", "FOO");

    Path buildFileOne = createFile("x/one/FOO");
    Path buildFileTwo = createFile("x/one/two/FOO");

    checkFindTargetPatternFrom(buildFileOne, "foo//one:");
    checkFindTargetPatternFrom(buildFileTwo, "foo//one/two:");

    checkFindTargetPatternFrom(createFile("x/a.bzl"), "foo//:a.bzl");
    checkFindTargetPatternFrom(createFile("x/one/a.bzl"), "foo//one:a.bzl");
    checkFindTargetPatternFrom(createFile("x/one/two/a.bzl"), "foo//one/two:a.bzl");
  }
}
