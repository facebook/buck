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
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.google.common.collect.ImmutableList;
import com.intellij.mock.MockVirtualFileSystem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuckCellFinderTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path tmpDir() throws IOException {
    return tmp.getRoot().getCanonicalFile().toPath();
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

  private BuckCell makeBuckCell(Path path) {
    BuckCell cell = new BuckCell();
    cell.setRoot(path.toString());
    cell.setName(path.getFileName().toString());
    return cell;
  }

  private BuckCell makeBuckCell(Path path, String buildfileName) {
    BuckCell cell = makeBuckCell(path);
    cell.setBuildFileName(buildfileName);
    return cell;
  }

  private MockVirtualFileSystem virtualFileSystem = new MockVirtualFileSystem();

  private VirtualFile toVirtualFile(Path path) {
    return virtualFileSystem.findFileByPath(path.toAbsolutePath().toString());
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

  @Test
  public void testFindBuckCellForPath() throws IOException {
    Path cellRoot = makeTmpDir("path/to/cell");
    BuckCell cell = makeBuckCell(cellRoot);
    BuckCell otherCell = makeBuckCell(makeTmpDir("foo/bar/baz"));
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(cell, otherCell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path topLevelFile = makeTmpFile(cellRoot.resolve("source"));
    Path lowerLevelFile = makeTmpFile(cellRoot.resolve("deep/in/cell/source"));
    Path nonCellFile = makeTmpFile("outside/cell/source");
    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);
    assertFindBuckCell(cell, finder, topLevelFile);
    assertFindBuckCell(cell, finder, lowerLevelFile);
    assertFindBuckCell(null, finder, nonCellFile);
  }

  @Test
  public void testFindBuckCellWhenNested() throws IOException {
    Path outerCellRoot = makeTmpDir("outer");
    Path innerCellRoot = makeTmpDir("outer/lib/inner");
    BuckCell outerCell = makeBuckCell(outerCellRoot);
    BuckCell innerCell = makeBuckCell(innerCellRoot);
    BuckCell otherCell = makeBuckCell(makeTmpDir("foo/bar/baz"));
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(outerCell, innerCell, otherCell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path nonCellFile = makeTmpFile("source");
    Path outerCellLevel0File = makeTmpFile("outer/source");
    Path outerCellLevel1File = makeTmpFile("outer/lib/source");
    Path innerCellLevel0File = makeTmpFile("outer/lib/inner/source");
    Path innerCellLevel1File = makeTmpFile("outer/lib/inner/tools/source");
    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);
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

  @Test
  public void testFindBuckFile() throws IOException {
    Path cellRoot = makeTmpDir("path/to/cell");
    BuckCell cell = makeBuckCell(cellRoot, "BINGO");
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(cell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path level1BuckFile = makeTmpFile(cellRoot.resolve("one/BINGO"));
    Path level3BuckFile = makeTmpFile(cellRoot.resolve("one/two/three/BINGO"));
    Path level0SourceFile = makeTmpFile(cellRoot.resolve("source"));
    Path level1SourceFile = makeTmpFile(cellRoot.resolve("one/source"));
    Path level2SourceFile = makeTmpFile(cellRoot.resolve("one/two/source"));
    Path level3SourceFile = makeTmpFile(cellRoot.resolve("one/two/three/source"));
    Path level4SourceFile = makeTmpFile(cellRoot.resolve("one/two/three/four/source"));
    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);
    assertFindBuckFile(null, finder, level0SourceFile);
    assertFindBuckFile(level1BuckFile, finder, level1SourceFile);
    assertFindBuckFile(level1BuckFile, finder, level2SourceFile);
    assertFindBuckFile(level3BuckFile, finder, level3SourceFile);
    assertFindBuckFile(level3BuckFile, finder, level4SourceFile);
  }

  @Test
  public void testFindBuckFileWhenNested() throws IOException {
    Path outerCellRoot = makeTmpDir("outer");
    Path innerCellRoot = makeTmpDir("outer/lib/inner");
    BuckCell outerCell = makeBuckCell(outerCellRoot, "OUTERBUCK");
    BuckCell innerCell = makeBuckCell(innerCellRoot, "INNERBUCK");
    BuckCell otherCell = makeBuckCell(makeTmpDir("foo/bar/baz"));
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(outerCell, innerCell, otherCell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path nonCellFile = makeTmpFile("source");
    Path outerCellLevel0SourceFile = makeTmpFile("outer/source");
    makeTmpFile("outer/lib/INNERBUCK"); // not a buck file for this cell!
    Path outerCellLevel1SourceFile = makeTmpFile("outer/lib/source");
    Path outerCellLevel1BuckFile = makeTmpFile("outer/lib/OUTERBUCK");
    Path innerCellLevel0SourceFile = makeTmpFile("outer/lib/inner/source");
    makeTmpFile("outer/lib/inner/OUTERBUCK"); // not a buck file for this cell!
    Path innerCellLevel1SourceFile = makeTmpFile("outer/lib/inner/tools/source");
    Path innerCellLevel1BuckFile = makeTmpFile("outer/lib/inner/tools/INNERBUCK");

    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);
    assertFindBuckFile(null, finder, nonCellFile);
    assertFindBuckFile(null, finder, outerCellLevel0SourceFile);
    assertFindBuckFile(outerCellLevel1BuckFile, finder, outerCellLevel1SourceFile);
    assertFindBuckFile(null, finder, innerCellLevel0SourceFile);
    assertFindBuckFile(innerCellLevel1BuckFile, finder, innerCellLevel1SourceFile);
  }

  private void assertFindBuckTargetFile(
      @Nullable Path expected, BuckCellFinder finder, Path sourcePath, String target) {
    Assert.assertEquals(
        "Should find buck file correctly for " + target + " starting from " + sourcePath.toString(),
        Optional.ofNullable(expected).map(this::toVirtualFile),
        finder.findBuckTargetFile(toVirtualFile(sourcePath), target));
  }

  @Test
  public void testFindTargetFile() throws IOException {
    Path fromRoot = makeTmpDir("from");
    BuckCell fromCell = makeBuckCell(fromRoot, "FROMBUCK");
    Path toRoot = makeTmpDir("to");
    BuckCell toCell = makeBuckCell(toRoot, "TOBUCK");
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(fromCell, toCell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path fromLevel0 = makeTmpFile("from/FROMBUCK");
    Path fromLevel1 = makeTmpFile("from/foo/FROMBUCK");
    Path toLevel0 = makeTmpFile("to/TOBUCK");
    Path toLevel1 = makeTmpFile("to/bar/TOBUCK");

    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);

    assertFindBuckTargetFile(fromLevel0, finder, fromLevel0, "//:any");
    assertFindBuckTargetFile(fromLevel0, finder, fromLevel1, "//:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel0, "//foo:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel1, "//foo:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "//foo/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "//foo/any:any");

    assertFindBuckTargetFile(fromLevel0, finder, fromLevel0, "from//:any");
    assertFindBuckTargetFile(fromLevel0, finder, fromLevel1, "from//:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel0, "from//foo:any");
    assertFindBuckTargetFile(fromLevel1, finder, fromLevel1, "from//foo:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "from//foo/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "from//foo/any:any");

    assertFindBuckTargetFile(toLevel0, finder, fromLevel0, "to//:any");
    assertFindBuckTargetFile(toLevel0, finder, fromLevel1, "to//:any");
    assertFindBuckTargetFile(toLevel1, finder, fromLevel0, "to//bar:any");
    assertFindBuckTargetFile(toLevel1, finder, fromLevel1, "to//bar:any");
    assertFindBuckTargetFile(null, finder, fromLevel0, "to//bar/any:any");
    assertFindBuckTargetFile(null, finder, fromLevel1, "to//bar/any:any");

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

  @Test
  public void testFindExtensionFile() throws IOException {
    Path fromRoot = makeTmpDir("from");
    BuckCell fromCell = makeBuckCell(fromRoot);
    Path toRoot = makeTmpDir("to");
    BuckCell toCell = makeBuckCell(toRoot);
    Project project = EasyMock.createMock(Project.class);
    BuckProjectSettingsProvider projectSettingsProvider =
        EasyMock.createMock(BuckProjectSettingsProvider.class);
    EasyMock.expect(projectSettingsProvider.getCells())
        .andReturn(ImmutableList.of(fromCell, toCell))
        .anyTimes();
    EasyMock.replay(project, projectSettingsProvider);

    Path fromLevel0BuckFile = makeTmpFile("from/BUCK");
    Path fromLevel0ExtFile = makeTmpFile("from/extension.bzl");
    Path fromLevel1BuckFile = makeTmpFile("from/foo/BUCK");
    Path fromLevel1ExtFile = makeTmpFile("from/foo/extension.bzl");
    Path toLevel0ExtFile = makeTmpFile("to/extension.bzl");
    Path toLevel1ExtFile = makeTmpFile("to/bar/extension.bzl");

    BuckCellFinder finder = new BuckCellFinder(project, projectSettingsProvider);

    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel0BuckFile, "//:extension.bzl");
    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel1BuckFile, "//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel0BuckFile, "//foo:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel1BuckFile, "//foo:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "//foo/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "//foo/any:extension.bzl");

    assertFindBuckExtensionFile(
        fromLevel0ExtFile, finder, fromLevel0BuckFile, "from//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel0ExtFile, finder, fromLevel1BuckFile, "from//:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel0BuckFile, "from//foo:extension.bzl");
    assertFindBuckExtensionFile(
        fromLevel1ExtFile, finder, fromLevel1BuckFile, "from//foo:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "from//foo/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "from//foo/any:extension.bzl");

    assertFindBuckExtensionFile(toLevel0ExtFile, finder, fromLevel0BuckFile, "to//:extension.bzl");
    assertFindBuckExtensionFile(toLevel0ExtFile, finder, fromLevel1BuckFile, "to//:extension.bzl");
    assertFindBuckExtensionFile(
        toLevel1ExtFile, finder, fromLevel0BuckFile, "to//bar:extension.bzl");
    assertFindBuckExtensionFile(
        toLevel1ExtFile, finder, fromLevel1BuckFile, "to//bar:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "to//bar/any:extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel1BuckFile, "to//bar/any:extension.bzl");

    assertFindBuckExtensionFile(fromLevel0ExtFile, finder, fromLevel0BuckFile, ":extension.bzl");
    assertFindBuckExtensionFile(fromLevel1ExtFile, finder, fromLevel1BuckFile, ":extension.bzl");

    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "bogus_path/extension.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, ":bogus_file.bzl");
    assertFindBuckExtensionFile(null, finder, fromLevel0BuckFile, "bogus_cell//:extension.bzl");
  }
}
