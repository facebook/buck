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

package com.facebook.buck.intellij.ideabuck.util;

import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.Assert;

public class BuckCellFinderTest extends PlatformTestCase {

  File tmpDir;
  BuckCellSettingsProvider buckCellSettingsProvider;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    tmpDir = this.createTempDirectory();
    buckCellSettingsProvider = new BuckCellSettingsProvider(getProject());
  }

  private Path tmpDir() throws IOException {
    return tmpDir.getCanonicalFile().toPath();
  }

  private Path makeTmpDir(Path path) throws IOException {
    Path tmpPath = tmpDir().resolve(path);
    File tmpFile = tmpPath.toFile();
    if (!tmpFile.exists()) {
      tmpFile.mkdirs();
    }
    return tmpPath;
  }

  private Path makeTmpFile(Path path) throws IOException {
    Path tmpPath = tmpDir().resolve(path);
    makeTmpDir(tmpPath.getParent());
    tmpPath.toFile().createNewFile();
    return tmpPath;
  }

  private Path makeTmpDir(String relativePath) throws IOException {
    return makeTmpDir(tmpDir().resolve(relativePath));
  }

  private Path makeTmpFile(String relativePath) throws IOException {
    return makeTmpFile(tmpDir().resolve(relativePath));
  }

  private BuckCell makeBuckCell(String name, String path) {
    BuckCell cell = new BuckCell();
    cell.setName(name);
    cell.setRoot(path);
    return cell;
  }

  private BuckCell makeBuckCell(String name, Path path) {
    return makeBuckCell(name, path.toString());
  }

  private BuckCell makeBuckCell(String name, Path path, String buildfileName) {
    BuckCell cell = makeBuckCell(name, path.toString());
    cell.setBuildFileName(buildfileName);
    return cell;
  }

  private void setUpCells(BuckCell... buckCells) {
    buckCellSettingsProvider.setCells(Arrays.asList(buckCells));
  }

  private VirtualFile toVirtualFile(Path path) {
    return LocalFileSystem.getInstance().findFileByPath(path.toAbsolutePath().toString());
  }

  public void testFindBuckCellByName() throws IOException {
    BuckCell thisCell = makeBuckCell("this", makeTmpDir("foo"));
    BuckCell thatCell = makeBuckCell("that", makeTmpDir("bar"));
    setUpCells(thisCell, thatCell);

    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);
    Assert.assertEquals(Optional.of(thisCell), finder.findBuckCellByName("this"));
    Assert.assertEquals(Optional.of(thatCell), finder.findBuckCellByName("that"));
    Assert.assertEquals(Optional.empty(), finder.findBuckCellByName("other"));
    Assert.assertEquals(
        "The empty cell name refers to the default cell",
        Optional.of(thisCell),
        finder.findBuckCellByName(""));
  }

  private void assertFindBuckCell(@Nullable BuckCell expected, BuckCellFinder finder, Path path) {
    Assert.assertEquals(
        "Should find buck cell correctly for Path",
        Optional.ofNullable(expected),
        finder.findBuckCell(path));
    Assert.assertEquals(
        "Should find buck cell correctly for File",
        Optional.ofNullable(expected),
        finder.findBuckCell(path.toFile()));
    Assert.assertEquals(
        "Should find buck cell correctly for VirtualFile",
        Optional.ofNullable(expected),
        finder.findBuckCell(toVirtualFile(path)));
  }

  public void testFindBuckCellForPath() throws IOException {
    Path cellRoot = makeTmpDir("path/to/cell");
    BuckCell cell = makeBuckCell("this", cellRoot);
    BuckCell otherCell = makeBuckCell("that", makeTmpDir("foo/bar/baz"));
    setUpCells(cell, otherCell);

    Path topLevelFile = makeTmpFile(cellRoot.resolve("source"));
    Path lowerLevelFile = makeTmpFile(cellRoot.resolve("deep/in/cell/source"));
    Path nonCellFile = makeTmpFile("outside/cell/source");
    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);
    assertFindBuckCell(cell, finder, topLevelFile);
    assertFindBuckCell(cell, finder, lowerLevelFile);
    assertFindBuckCell(null, finder, nonCellFile);
  }

  public void testFindBuckCellWhenNested() throws IOException {
    Path outerCellRoot = makeTmpDir("outer");
    Path innerCellRoot = makeTmpDir("outer/lib/inner");
    BuckCell outerCell = makeBuckCell("outer", outerCellRoot);
    BuckCell innerCell = makeBuckCell("inner", innerCellRoot);
    BuckCell otherCell = makeBuckCell("other", makeTmpDir("foo/bar/baz"));
    setUpCells(outerCell, innerCell, otherCell);

    Path nonCellFile = makeTmpFile("source");
    Path outerCellLevel0File = makeTmpFile("outer/source");
    Path outerCellLevel1File = makeTmpFile("outer/lib/source");
    Path innerCellLevel0File = makeTmpFile("outer/lib/inner/source");
    Path innerCellLevel1File = makeTmpFile("outer/lib/inner/tools/source");
    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);
    assertFindBuckCell(null, finder, nonCellFile);
    assertFindBuckCell(outerCell, finder, outerCellLevel0File);
    assertFindBuckCell(outerCell, finder, outerCellLevel1File);
    assertFindBuckCell(innerCell, finder, innerCellLevel0File);
    assertFindBuckCell(innerCell, finder, innerCellLevel1File);
  }

  private void assertFindBuckFile(@Nullable Path expected, BuckCellFinder finder, Path path) {
    Assert.assertEquals(
        "Should find buck file correctly for Path",
        Optional.ofNullable(expected),
        finder.findBuckFile(path));
    Assert.assertEquals(
        "Should find buck file correctly for File",
        Optional.ofNullable(expected).map(Path::toFile),
        finder.findBuckFile(path.toFile()));
    Assert.assertEquals(
        "Should find buck file correctly for VirtualFile",
        Optional.ofNullable(expected).map(this::toVirtualFile),
        finder.findBuckFile(toVirtualFile(path)));
  }

  public void testFindBuckFile() throws IOException {
    Path cellRoot = makeTmpDir("path/to/cell");
    BuckCell cell = makeBuckCell("", cellRoot, "BINGO");
    setUpCells(cell);

    Path level1BuckFile = makeTmpFile(cellRoot.resolve("one/BINGO"));
    Path level3BuckFile = makeTmpFile(cellRoot.resolve("one/two/three/BINGO"));
    Path level0SourceFile = makeTmpFile(cellRoot.resolve("source"));
    Path level1SourceFile = makeTmpFile(cellRoot.resolve("one/source"));
    Path level2SourceFile = makeTmpFile(cellRoot.resolve("one/two/source"));
    Path level3SourceFile = makeTmpFile(cellRoot.resolve("one/two/three/source"));
    Path level4SourceFile = makeTmpFile(cellRoot.resolve("one/two/three/four/source"));
    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);
    assertFindBuckFile(null, finder, level0SourceFile);
    assertFindBuckFile(level1BuckFile, finder, level1SourceFile);
    assertFindBuckFile(level1BuckFile, finder, level2SourceFile);
    assertFindBuckFile(level3BuckFile, finder, level3SourceFile);
    assertFindBuckFile(level3BuckFile, finder, level4SourceFile);
  }

  public void testFindBuckFileWhenNested() throws IOException {
    Path outerCellRoot = makeTmpDir("outer");
    Path innerCellRoot = makeTmpDir("outer/lib/inner");
    BuckCell outerCell = makeBuckCell("outer", outerCellRoot, "OUTERBUCK");
    BuckCell innerCell = makeBuckCell("inner", innerCellRoot, "INNERBUCK");
    BuckCell otherCell = makeBuckCell("other", makeTmpDir("foo/bar/baz"));
    setUpCells(outerCell, innerCell, otherCell);

    Path nonCellFile = makeTmpFile("source");
    Path outerCellLevel0SourceFile = makeTmpFile("outer/source");
    makeTmpFile("outer/lib/INNERBUCK"); // not a buck file for this cell!
    Path outerCellLevel1SourceFile = makeTmpFile("outer/lib/source");
    Path outerCellLevel1BuckFile = makeTmpFile("outer/lib/OUTERBUCK");
    Path innerCellLevel0SourceFile = makeTmpFile("outer/lib/inner/source");
    makeTmpFile("outer/lib/inner/OUTERBUCK"); // not a buck file for this cell!
    Path innerCellLevel1SourceFile = makeTmpFile("outer/lib/inner/tools/source");
    Path innerCellLevel1BuckFile = makeTmpFile("outer/lib/inner/tools/INNERBUCK");

    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);
    assertFindBuckFile(null, finder, nonCellFile);
    assertFindBuckFile(null, finder, outerCellLevel0SourceFile);
    assertFindBuckFile(outerCellLevel1BuckFile, finder, outerCellLevel1SourceFile);
    assertFindBuckFile(null, finder, innerCellLevel0SourceFile);
    assertFindBuckFile(innerCellLevel1BuckFile, finder, innerCellLevel1SourceFile);
  }

  private void assertFindBuckTargetFile(
      @Nullable Path expectedTargetFile, BuckCellFinder finder, Path sourcePath, String target) {
    Optional<VirtualFile> actualTargetFile =
        finder.findBuckTargetFile(toVirtualFile(sourcePath), target);
    Assert.assertEquals(
        "Should find buck file correctly for " + target + " starting from " + sourcePath.toString(),
        Optional.ofNullable(expectedTargetFile).map(this::toVirtualFile),
        actualTargetFile);
  }

  public void testFindTargetFile() throws IOException {
    Path fromRoot = makeTmpDir("from");
    BuckCell fromCell = makeBuckCell("src", fromRoot, "FROMBUCK");
    Path toRoot = makeTmpDir("to");
    BuckCell toCell = makeBuckCell("dst", toRoot, "TOBUCK");
    setUpCells(fromCell, toCell);

    Path fromLevel0 = makeTmpFile("from/FROMBUCK");
    Path fromLevel1 = makeTmpFile("from/foo/FROMBUCK");
    Path toLevel0 = makeTmpFile("to/TOBUCK");
    Path toLevel1 = makeTmpFile("to/bar/TOBUCK");

    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);

    assertFindBuckTargetFile(fromLevel0, finder, fromLevel0, "//:any");
    assertFindBuckTargetFile(fromLevel0, finder, fromLevel1, "//:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel0, "//foo:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel1, "//foo:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "//foo/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "//foo/any:any");

    assertFindBuckTargetFile(fromLevel0, finder, fromLevel0, "src//:any");
    assertFindBuckTargetFile(fromLevel0, finder, fromLevel1, "src//:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel0, "src//foo:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel1, "src//foo:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "src//foo/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "src//foo/any:any");

    assertFindBuckTargetFile(toLevel0, finder, fromLevel0, "dst//:any");
    assertFindBuckTargetFile(toLevel0, finder, fromLevel1, "dst//:any");
    assertFindBuckTargetFile(toLevel1, finder, fromLevel0, "dst//bar:any");
    assertFindBuckTargetFile(toLevel1, finder, fromLevel1, "dst//bar:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "dst//bar/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "dst//bar/any:any");

    assertFindBuckTargetFile(null, finder, fromLevel0, "not/absolute/path");
    assertFindBuckTargetFile(null, finder, fromLevel0, ":not_absolute_target");
    assertFindBuckTargetFile(null, finder, fromLevel0, "bogus_cell//:FROMBUCK");
  }

  private void assertFindBuckExtensionFile(
      @Nullable Path expected, BuckCellFinder finder, Path sourcePath, String target) {
    Assert.assertEquals(
        "Should find extension file correctly for "
            + target
            + " starting from "
            + sourcePath.toString(),
        Optional.ofNullable(expected).map(this::toVirtualFile),
        finder.findExtensionFile(toVirtualFile(sourcePath), target));
  }

  public void testFindExtensionFile() throws IOException {
    Path fromRoot = makeTmpDir("from");
    BuckCell fromCell = makeBuckCell("src", fromRoot);
    Path toRoot = makeTmpDir("to");
    BuckCell toCell = makeBuckCell("dst", toRoot);
    setUpCells(fromCell, toCell);

    Path fromLevel0BuckFile = makeTmpFile("from/BUCK");
    Path fromLevel0ExtFile = makeTmpFile("from/extension.bzl");
    Path fromLevel1BuckFile = makeTmpFile("from/foo/BUCK");
    Path fromLevel1ExtFile = makeTmpFile("from/foo/extension.bzl");
    Path toLevel0ExtFile = makeTmpFile("to/extension.bzl");
    Path toLevel1ExtFile = makeTmpFile("to/bar/extension.bzl");

    BuckCellFinder finder = new BuckCellFinder(getProject(), buckCellSettingsProvider, s -> s);

    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel0BuckFile, "//:extension.bzl");
    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel1BuckFile, "//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel0BuckFile, "//foo:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel1BuckFile, "//foo:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "//foo/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "//foo/any:extension.bzl");

    assertFindBuckExtensionFile(
        fromLevel0ExtFile, finder, fromLevel0BuckFile, "src//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel0ExtFile, finder, fromLevel1BuckFile, "src//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel0BuckFile, "src//foo:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel1BuckFile, "src//foo:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "src//foo/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "src//foo/any:extension.bzl");

    assertFindBuckExtensionFile(toLevel0ExtFile, finder, fromLevel0BuckFile, "dst//:extension.bzl");
    assertFindBuckExtensionFile(toLevel0ExtFile, finder, fromLevel1BuckFile, "dst//:extension.bzl");
    assertFindBuckExtensionFile(
        toLevel1ExtFile, finder, fromLevel0BuckFile, "dst//bar:extension.bzl");
    assertFindBuckExtensionFile(
        toLevel1ExtFile, finder, fromLevel1BuckFile, "dst//bar:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "dst//bar/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "dst//bar/any:extension.bzl");

    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel0BuckFile, ":extension.bzl");
    assertFindBuckExtensionFile(fromLevel1ExtFile, finder, fromLevel1BuckFile, ":extension.bzl");

    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "bogus_path/extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, ":bogus_file.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "bogus_cell//:extension.bzl");
  }

  public void testFindBuckFileRespectsPathExpansion() throws IOException {
    Path userHome = makeTmpDir("user_home");
    Path projectDir = makeTmpDir("user_home/project");
    Path internalCellDir = makeTmpDir("user_home/project/internal");
    Path externalCellDir = makeTmpDir("user_home/dev/external");
    BuckCell projectCell = makeBuckCell("main", "$PROJECT_DIR$");
    BuckCell internalCell = makeBuckCell("internal", "$PROJECT_DIR$/internal");
    BuckCell externalCell = makeBuckCell("external", "$USER_HOME$/dev/external");
    setUpCells(projectCell, internalCell, externalCell);

    BuckCellFinder finder =
        new BuckCellFinder(
            getProject(),
            buckCellSettingsProvider,
            s ->
                s.replace("$PROJECT_DIR$", projectDir.toString())
                    .replace("$USER_HOME$", userHome.toString()));

    Path projectCellBuckFile = makeTmpFile(projectDir.resolve("BUCK"));
    Path internalCellBuckFile = makeTmpFile(internalCellDir.resolve("BUCK"));
    Path externalCellBuckFile = makeTmpFile(externalCellDir.resolve("BUCK"));

    Path projectCellSourceFile = makeTmpFile(projectDir.resolve("foo/source"));
    Path internalCellSourceFile = makeTmpFile(internalCellDir.resolve("bar/source"));
    Path externalCellSourceFile = makeTmpFile(externalCellDir.resolve("baz/source"));
    Path notInACellSourceFile = makeTmpFile("user_home/other");

    assertFindBuckFile(null, finder, notInACellSourceFile);
    assertFindBuckFile(projectCellBuckFile, finder, projectCellSourceFile);
    assertFindBuckFile(internalCellBuckFile, finder, internalCellSourceFile);
    assertFindBuckFile(externalCellBuckFile, finder, externalCellSourceFile);
  }
}
