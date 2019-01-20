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

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Cross-cell navigation helper.
 *
 * @deprecated Use either the {@link BuckCellManager} or the {@link BuckTargetLocator} for a stable
 *     API.
 */
public class BuckCellFinder implements ProjectComponent {

  public static BuckCellFinder getInstance(Project project) {
    return project.getComponent(BuckCellFinder.class);
  }

  private BuckCellManager buckCellManager;
  private BuckTargetLocator buckTargetLocator;

  public BuckCellFinder(BuckCellManager buckCellManager, BuckTargetLocator buckTargetLocator) {
    this.buckCellManager = buckCellManager;
    this.buckTargetLocator = buckTargetLocator;
  }

  static BuckCell toBuckCell(Cell cell) {
    BuckCell buckCell = new BuckCell();
    cell.getName().ifPresent(buckCell::setName);
    buckCell.setBuildFileName(cell.getBuildfileName());
    buckCell.setRoot(cell.getRootPath().toString());
    return buckCell;
  }

  /**
   * Returns the {@link BuckCell} with the given name. Supplying the empty string returns the
   * default cell, regardless of what it is called.
   *
   * @deprecated Use {@link BuckCellManager#findCellByName(String)}.
   */
  @Deprecated
  public Optional<BuckCell> findBuckCellByName(String name) {
    return buckCellManager.findCellByName(name).map(BuckCellFinder::toBuckCell);
  }

  /**
   * Returns the {@link BuckCell} containing the given {@link VirtualFile}.
   *
   * @deprecated Use {@link BuckCellManager#findCellByVirtualFile(VirtualFile)}.
   */
  @Deprecated
  public Optional<BuckCell> findBuckCell(VirtualFile file) {
    return buckCellManager.findCellByVirtualFile(file).map(BuckCellFinder::toBuckCell);
  }

  /**
   * Returns the {@link BuckCell} containing the given {@link Path}.
   *
   * @deprecated Use {@link BuckCellManager#findCellByPath(Path)}.
   */
  @Deprecated
  public Optional<BuckCell> findBuckCell(Path path) {
    return buckCellManager.findCellByPath(path).map(BuckCellFinder::toBuckCell);
  }

  /**
   * Returns the {@link BuckCell} containing the given {@link File}.
   *
   * @deprecated Use {@link BuckCellManager#findCellByPath(Path)}.
   */
  @Deprecated
  public Optional<BuckCell> findBuckCell(File file) {
    return buckCellManager.findCellByPath(file.toPath()).map(BuckCellFinder::toBuckCell);
  }

  /**
   * Returns the {@link BuckCell} from the given canonical path.
   *
   * @deprecated Use {@link BuckCellManager#findCellByPath(Path)}.
   */
  @Deprecated
  public Optional<BuckCell> findBuckCellFromCanonicalPath(String canonicalPath) {
    return buckCellManager.findCellByPath(Paths.get(canonicalPath)).map(BuckCellFinder::toBuckCell);
  }

  /**
   * Finds the Buck file most closely associated with the given file.
   *
   * @deprecated Use {@link BuckTargetLocator#findBuckFileForPath(Path)}.
   */
  @Deprecated
  public Optional<File> findBuckFile(File file) {
    return buckTargetLocator.findBuckFileForPath(file.toPath()).map(Path::toFile);
  }

  /**
   * Finds the Buck file most closely associated with the given file.
   *
   * @deprecated Use {@link BuckTargetLocator#findBuckFileForPath(Path)}.
   */
  @Deprecated
  public Optional<Path> findBuckFile(Path path) {
    return buckTargetLocator.findBuckFileForPath(path);
  }

  /**
   * Finds the Buck file most closely associated with the given file.
   *
   * @deprecated
   */
  @Deprecated
  public Optional<VirtualFile> findBuckFile(VirtualFile file) {
    return buckTargetLocator.findBuckFileForVirtualFile(file);
  }

  /** Returns the Buck cell for the given target, starting from the given sourceFile. */
  @Deprecated
  public Optional<BuckCell> findBuckCell(VirtualFile sourceFile, String cellName) {
    if ("".equals(cellName)) {
      return findBuckCell(sourceFile);
    } else {
      return findBuckCellByName(cellName);
    }
  }

  /**
   * Finds the Buck file for the given target, starting from the given sourceFile.
   *
   * @deprecated Use the find and resolve methods from {@link BuckTargetLocator}.
   */
  @Deprecated
  public Optional<VirtualFile> findBuckTargetFile(VirtualFile sourceFile, String target) {
    return BuckTarget.parse(target)
        .flatMap(t -> buckTargetLocator.resolve(sourceFile, t))
        .flatMap(t -> buckTargetLocator.findVirtualFileForTarget(t));
  }

  /**
   * Finds an extension file, starting from the given sourceFile.
   *
   * @deprecated Use the find and resolve methods from {@link BuckTargetLocator}.
   */
  @Deprecated
  public Optional<VirtualFile> findExtensionFile(VirtualFile sourceFile, String target) {
    return BuckTarget.parse(target)
        .flatMap(t -> buckTargetLocator.resolve(sourceFile, t))
        .flatMap(t -> buckTargetLocator.findVirtualFileForExtensionFile(t));
  }

  /**
   * Resolves the given {@code path} in the expected form {@code <cell>//<path>} (any target is
   * optional/ignored).
   *
   * <p>Note that there is no guarantee about the type of {@code VirtualFile} returned, so users who
   * require it to be either a directory or a file should check.
   *
   * @deprecated Use the find and resolve methods from {@link BuckTargetLocator}.
   */
  @Deprecated
  public Optional<VirtualFile> resolveCellPath(VirtualFile sourceFile, String path) {
    return BuckTargetPattern.parse(path)
        .flatMap(p -> buckTargetLocator.resolve(sourceFile, p))
        .flatMap(p -> buckTargetLocator.findVirtualFileForTargetPattern(p));
  }
}
