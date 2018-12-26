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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-cell navigation helper. */
public class BuckCellFinder implements ProjectComponent {
  public static BuckCellFinder getInstance(Project project) {
    return project.getComponent(BuckCellFinder.class);
  }

  private BuckCellSettingsProvider buckCellSettingsProvider;
  private Function<String, String> pathMacroExpander;

  public BuckCellFinder(
      BuckCellSettingsProvider buckCellSettingsProvider, PathMacroManager pathMacroManager) {
    this(buckCellSettingsProvider, pathMacroManager::expandPath);
  }

  @VisibleForTesting
  BuckCellFinder(
      BuckCellSettingsProvider buckCellSettingsProvider,
      Function<String, String> pathMacroExpander) {
    this.buckCellSettingsProvider = buckCellSettingsProvider;
    this.pathMacroExpander = pathMacroExpander;
  }

  /**
   * Returns the {@link BuckCell} with the given name. Supplying the empty string returns the
   * default cell, regardless of what it is called.
   */
  public Optional<BuckCell> findBuckCellByName(String name) {
    return buckCellSettingsProvider
        .getCells()
        .stream()
        .filter(c -> "".equals(name) || c.getName().equals(name))
        .findFirst();
  }

  /** Returns the {@link BuckCell} containing the given {@link VirtualFile}. */
  public Optional<BuckCell> findBuckCell(VirtualFile file) {
    return findBuckCellFromCanonicalPath(file.getCanonicalPath());
  }

  /** Returns the {@link BuckCell} containing the given {@link Path}. */
  public Optional<BuckCell> findBuckCell(Path path) {
    return findBuckCell(path.toFile());
  }

  /** Returns the {@link BuckCell} containing the given {@link File}. */
  public Optional<BuckCell> findBuckCell(File file) {
    try {
      return findBuckCellFromCanonicalPath(file.getCanonicalPath());
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Returns the {@link BuckCell} from the given canonical path. */
  public Optional<BuckCell> findBuckCellFromCanonicalPath(String canonicalPath) {
    return buckCellSettingsProvider
        .getCells()
        .stream()
        .filter(
            cell -> {
              String root = pathMacroExpander.apply(cell.getRoot());
              return canonicalPath.equals(root) || canonicalPath.startsWith(root + File.separator);
            })
        .max(Ordering.natural().onResultOf(cell -> cell.getRoot().length()));
  }

  /** Finds the Buck file most closely associated with the given file. */
  public Optional<File> findBuckFile(File file) {
    return findBuckCell(file)
        .flatMap(
            cell -> {
              String cellRoot = pathMacroExpander.apply(cell.getRoot());
              int cellRootLength = cellRoot.length();
              String buildFilename = cell.getBuildFileName();
              File parent = file;
              while (parent.toString().length() >= cellRootLength) {
                File buckFile = new File(parent, buildFilename);
                if (buckFile.isFile()) {
                  return Optional.of(buckFile);
                }
                parent = parent.getParentFile();
              }
              return Optional.empty();
            });
  }

  /** Finds the Buck file most closely associated with the given path. */
  public Optional<Path> findBuckFile(Path path) {
    return findBuckFile(path.toFile()).map(File::toPath);
  }

  /** Finds the Buck file most closely associated with the given file. */
  public Optional<VirtualFile> findBuckFile(VirtualFile file) {
    return findBuckCell(file)
        .flatMap(
            cell -> {
              String cellRoot = pathMacroExpander.apply(cell.getRoot());
              int cellRootLength = cellRoot.length();
              String buildFilename = cell.getBuildFileName();
              VirtualFile parent = file;
              while (parent.getCanonicalPath().length() >= cellRootLength) {
                VirtualFile buckFile = parent.findChild(buildFilename);
                if (buckFile != null && buckFile.exists() && !buckFile.isDirectory()) {
                  return Optional.of(buckFile);
                }
                parent = parent.getParent();
                if (parent == null) {
                  break;
                }
                if (parent.getCanonicalPath() == null) {
                  break;
                }
              }
              return Optional.empty();
            });
  }

  /** Returns the Buck cell for the given target, starting from the given sourceFile. */
  public Optional<BuckCell> findBuckCell(VirtualFile sourceFile, String cellName) {
    if ("".equals(cellName)) {
      return findBuckCell(sourceFile);
    } else {
      return findBuckCellByName(cellName);
    }
  }

  /**
   * Matches buck's cell notation: an optional @ in front of a cell name, a cell path, and an
   * (optional) target.
   */
  private static final Pattern BUCK_CELL_PATH_PATTERN =
      Pattern.compile("@?(?<cell>[-A-Za-z_.]*)//(?<path>[^:]*)(:(?<target>[^:]*))?");

  /** Finds the Buck file for the given target, starting from the given sourceFile. */
  public Optional<VirtualFile> findBuckTargetFile(VirtualFile sourceFile, String target) {
    Matcher matcher = BUCK_CELL_PATH_PATTERN.matcher(target);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String targetName = matcher.group("target");
    if (targetName == null) {
      return Optional.empty();
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    return findBuckCell(sourceFile, cellName)
        .flatMap(
            cell -> {
              return Optional.ofNullable(pathMacroExpander.apply(cell.getRoot()))
                  .map(s -> sourceFile.getFileSystem().findFileByPath(s))
                  .map(root -> VfsUtil.findRelativeFile(cellPath, root))
                  .map(sub -> VfsUtil.findRelativeFile(cell.getBuildFileName(), sub))
                  .filter(VirtualFile::exists);
            });
  }

  /** Finds an extension file, starting from the given sourceFile. */
  public Optional<VirtualFile> findExtensionFile(VirtualFile sourceFile, String target) {
    if (target.startsWith(":")) {
      return Optional.ofNullable(sourceFile.getParent())
          .map(f -> f.findFileByRelativePath(target.substring(1)))
          .filter(VirtualFile::exists);
    }
    Matcher matcher = BUCK_CELL_PATH_PATTERN.matcher(target);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String targetName = matcher.group("target");
    if (targetName == null) {
      return Optional.empty();
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    return findBuckCell(sourceFile, cellName)
        .flatMap(
            cell -> {
              return Optional.ofNullable(pathMacroExpander.apply(cell.getRoot()))
                  .map(s -> sourceFile.getFileSystem().findFileByPath(s))
                  .map(cellRoot -> cellRoot.findFileByRelativePath(cellPath))
                  .map(subDir -> subDir.findFileByRelativePath(targetName))
                  .filter(VirtualFile::exists);
            });
  }

  /**
   * Resolves the given {@code path} in the expected form {@code <cell>//<path>} (any target is
   * optional/ignored).
   *
   * <p>Note that there is no guarantee about the type of {@code VirtualFile} returned, so users who
   * require it to be either a directory or a file should
   */
  public Optional<VirtualFile> resolveCellPath(VirtualFile sourceFile, String path) {
    Matcher matcher = BUCK_CELL_PATH_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    return findBuckCell(sourceFile, cellName)
        .flatMap(
            cell -> {
              return Optional.ofNullable(pathMacroExpander.apply(cell.getRoot()))
                  .map(s -> sourceFile.getFileSystem().findFileByPath(s))
                  .map(cellRoot -> cellRoot.findFileByRelativePath(cellPath));
            });
  }
}
