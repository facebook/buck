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

package com.facebook.buck.intellij.ideabuck;

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
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

public abstract class PlatformTestCaseWithBuckCells extends PlatformTestCase {
  protected Project project;
  protected BuckCellSettingsProvider buckCellSettingsProvider;
  protected BuckCell defaultCell;
  protected BuckTargetLocator buckTargetLocator;
  protected VirtualFile tempDir;

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
  protected <T> T unwrap(Optional<T> optional) {
    T t = optional.orElse(null);
    assertNotNull(t);
    return t;
  }

  protected <T> void assertOptionalEquals(T expected, Optional<T> actual) {
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

  protected Path createFile(BuckCell cell, String cellRelativePath) {
    return createFile(cell, cellRelativePath, "Test:" + getName());
  }

  protected Path createFile(BuckCell cell, String cellRelativePath, String content) {
    try {
      String basePath = project.getBasePath();
      String root = cell.getRoot().replace("$PROJECT_DIR$", basePath);
      Path path = Paths.get(root).resolve(cellRelativePath);
      path.getParent().toFile().mkdirs();
      try (FileWriter writer = new FileWriter(path.toFile())) {
        writer.write(content);
      }
      return path;
    } catch (IOException e) {
      fail("Failed to create test file: " + e.getMessage());
      throw new RuntimeException("Failed");
    }
  }

  protected Path createFileInDefaultCell(String defaultCellRelativePath) {
    return createFile(defaultCell, defaultCellRelativePath);
  }

  protected Path createFileInDefaultCell(String defaultCellRelativePath, String content) {
    return createFile(defaultCell, defaultCellRelativePath, content);
  }

  protected VirtualFile asVirtualFile(@Nullable Path expectedPath) {
    if (expectedPath == null) {
      return null;
    }
    return project.getBaseDir().getFileSystem().refreshAndFindFileByPath(expectedPath.toString());
  }
}
