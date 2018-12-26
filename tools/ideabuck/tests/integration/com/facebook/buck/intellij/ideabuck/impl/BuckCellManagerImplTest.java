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

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.facebook.buck.intellij.ideabuck.impl.BuckCellManagerImpl.CellImpl;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.PlatformTestCase;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

public class BuckCellManagerImplTest extends PlatformTestCase {

  private VirtualFileManager virtualFileManager;
  private PathMacroManager pathMacroManager;
  private BuckCellSettingsProvider buckCellSettingsProvider;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Project project = getProject();
    virtualFileManager = VirtualFileManager.getInstance();
    pathMacroManager = PathMacroManager.getInstance(project);
    buckCellSettingsProvider = BuckCellSettingsProvider.getInstance(project);
  }

  private Path createFile(Path path) {
    try {
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

  private VirtualFile createVirtualFile(VirtualFile root, String relativePath) {
    Path targetPath = createFile(Paths.get(root.getPath()).resolve(relativePath));
    return root.getFileSystem().findFileByPath(targetPath.toString());
  }

  private BuckCell createCell(String name, String root) {
    String expandedPath = pathMacroManager.expandPath(root);
    try {
      Files.createDirectories(Paths.get(expandedPath));
    } catch (IOException e) {
      e.printStackTrace();
      fail("Failed to create directories for cell: " + e.getMessage());
    }
    return new BuckCell().withName(name).withRoot(expandedPath);
  }

  @Nonnull
  private BuckCellManagerImpl createBuckCellManager() {
    return new BuckCellManagerImpl(
        getProject(), buckCellSettingsProvider, virtualFileManager, pathMacroManager);
  }

  public void testGetNamedDefaultCell() {
    BuckCell fooCell = createCell("foo", "$PROJECT_DIR$/foo").withBuildFileName("FOOBUCK");
    VirtualFile fooRoot = getProject().getBaseDir().findChild("foo");
    Path fooPath = Paths.get(fooRoot.getPath());
    assertNotNull(fooRoot);
    BuckCell barCell = createCell("bar", "$PROJECT_DIR$/bar").withBuildFileName("BARBUCK");
    buckCellSettingsProvider.setCells(Arrays.asList(fooCell, barCell));

    BuckCellManagerImpl buckCellManager = createBuckCellManager();
    CellImpl defaultCell = buckCellManager.getDefaultCell().orElse(null);
    assertNotNull(defaultCell);

    assertEquals(fooCell, defaultCell.getBuckCell());

    assertEquals(Optional.of("foo"), defaultCell.getName());
    assertEquals(Optional.ofNullable(fooRoot), defaultCell.getRootDirectory());
    assertEquals(fooPath, defaultCell.getRootPath());
    assertEquals("FOOBUCK", defaultCell.getBuildfileName());
  }

  public void testGetUnnamedDefaultCell() {
    BuckCell defaultBuckCell = createCell("", "$PROJECT_DIR$");
    buckCellSettingsProvider.setCells(Collections.singletonList(defaultBuckCell));

    BuckCellManagerImpl buckCellManager = createBuckCellManager();
    CellImpl defaultCell = buckCellManager.getDefaultCell().orElse(null);
    assertNotNull(defaultCell);

    assertEquals(defaultBuckCell, defaultCell.getBuckCell());
  }

  public void testGetCells() {
    BuckCell fooBuckCell = createCell("foo", "$PROJECT_DIR$/foo");
    BuckCell barBuckCell = createCell("bar", "$PROJECT_DIR$/bar");
    List<BuckCell> expectedCells = Arrays.asList(fooBuckCell, barBuckCell);

    buckCellSettingsProvider.setCells(expectedCells);

    BuckCellManagerImpl buckCellManager = createBuckCellManager();

    List<? extends Cell> actualCells = buckCellManager.getCells();
    assertEquals(fooBuckCell.getName(), actualCells.get(0).getName().get());
    assertEquals(fooBuckCell.getRoot(), actualCells.get(0).getRootPath().toString());
    assertEquals(barBuckCell.getName(), actualCells.get(1).getName().get());
    assertEquals(barBuckCell.getRoot(), actualCells.get(1).getRootPath().toString());
  }

  public void testFindCellByName() {
    BuckCell fooBuckCell = createCell("foo", "$PROJECT_DIR$/foo");
    BuckCell barBuckCell = createCell("bar", "$PROJECT_DIR$/bar");
    buckCellSettingsProvider.setCells(Arrays.asList(fooBuckCell, barBuckCell));

    BuckCellManagerImpl buckCellManager = createBuckCellManager();

    assertEquals(
        Optional.of(fooBuckCell), buckCellManager.findCellByName("foo").map(CellImpl::getBuckCell));
    assertEquals(
        Optional.of(barBuckCell), buckCellManager.findCellByName("bar").map(CellImpl::getBuckCell));
    assertEquals(Optional.empty(), buckCellManager.findCellByName("baz"));
  }

  public void testFindCellByVirtualFile() {
    VirtualFile projectDir = getProject().getBaseDir();
    BuckCell fooCell = createCell("foo", "$PROJECT_DIR$/foo");
    BuckCell barCell = createCell("bar", "$PROJECT_DIR$/bar");
    buckCellSettingsProvider.setCells(Arrays.asList(fooCell, barCell));

    VirtualFile fileInFooCell = createVirtualFile(projectDir, "foo/eff/oh/oh");
    VirtualFile fileInBarCell = createVirtualFile(projectDir, "bar/bee/ay/ar");
    VirtualFile fileInNoCell = createVirtualFile(projectDir, "non/cell/file");

    BuckCellManagerImpl buckCellManager = createBuckCellManager();

    assertEquals(
        Optional.of(fooCell),
        buckCellManager.findCellByVirtualFile(fileInFooCell).map(CellImpl::getBuckCell));
    assertEquals(
        Optional.of(barCell),
        buckCellManager.findCellByVirtualFile(fileInBarCell).map(CellImpl::getBuckCell));
    assertEquals(Optional.empty(), buckCellManager.findCellByVirtualFile(fileInNoCell));
  }

  public void testFindCellByPath() {
    Path projectDir = Paths.get(getProject().getBasePath());
    BuckCell fooCell = createCell("foo", "$PROJECT_DIR$/foo");
    BuckCell barCell = createCell("bar", "$PROJECT_DIR$/bar");
    buckCellSettingsProvider.setCells(Arrays.asList(fooCell, barCell));

    Path pathInFooCell = createFile(projectDir.resolve("foo/eff/oh/oh"));
    Path pathInBarCell = createFile(projectDir.resolve("bar/bee/ay/ar"));
    Path pathInNoCell = createFile(projectDir.resolve("non/cell/file"));

    BuckCellManagerImpl buckCellManager = createBuckCellManager();

    assertEquals(
        Optional.of(fooCell),
        buckCellManager.findCellByPath(pathInFooCell).map(CellImpl::getBuckCell));
    assertEquals(
        Optional.of(barCell),
        buckCellManager.findCellByPath(pathInBarCell).map(CellImpl::getBuckCell));
    assertEquals(Optional.empty(), buckCellManager.findCellByPath(pathInNoCell));
  }

  public void testFindCellByPathWhenCellsAreNested() {
    Path projectDir = Paths.get(getProject().getBasePath());
    BuckCell outerCell = createCell("outer", "$PROJECT_DIR$/one");
    BuckCell middleCell = createCell("middle", "$PROJECT_DIR$/one/two");
    BuckCell innerCell = createCell("inner", "$PROJECT_DIR$/one/two/three");
    buckCellSettingsProvider.setCells(Arrays.asList(middleCell, innerCell, outerCell));

    Path pathInInnerCell = createFile(projectDir.resolve("one/two/three/x/y/z"));
    Path pathInMiddleCell = createFile(projectDir.resolve("one/two/i/c/u"));
    Path pathInOuterCell = createFile(projectDir.resolve("one/and/done"));
    Path pathInNoCell = createFile(projectDir.resolve("not/in/a/cell"));

    BuckCellManagerImpl buckCellManager = createBuckCellManager();

    assertEquals(
        Optional.of(outerCell),
        buckCellManager.findCellByPath(pathInOuterCell).map(CellImpl::getBuckCell));
    assertEquals(
        Optional.of(middleCell),
        buckCellManager.findCellByPath(pathInMiddleCell).map(CellImpl::getBuckCell));
    assertEquals(
        Optional.of(innerCell),
        buckCellManager.findCellByPath(pathInInnerCell).map(CellImpl::getBuckCell));
    assertEquals(Optional.empty(), buckCellManager.findCellByPath(pathInNoCell));
  }
}
