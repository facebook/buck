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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-cell navigation helper. */
public class BuckCellFinder extends AbstractProjectComponent {
  public static BuckCellFinder getInstance(Project project) {
    return new BuckCellFinder(project);
  }

  private BuckProjectSettingsProvider projectSettingsProvider;

  public BuckCellFinder(Project project) {
    this(project, BuckProjectSettingsProvider.getInstance(project));
  }

  @VisibleForTesting
  BuckCellFinder(Project project, BuckProjectSettingsProvider projectSettingsProvider) {
    super(project);
    this.projectSettingsProvider = projectSettingsProvider;
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
    return projectSettingsProvider
        .getCells()
        .stream()
        .filter(
            cell -> {
              String root = cell.getRoot();
              return canonicalPath.equals(root) || canonicalPath.startsWith(root + File.separator);
            })
        .max(Ordering.natural().onResultOf(cell -> cell.getRoot().length()));
  }

  /** Finds the Buck file most closely associated with the given file. */
  public Optional<File> findBuckFile(File file) {
    return findBuckCell(file)
        .flatMap(
            cell -> {
              int cellRootLength = cell.getRoot().length();
              String buildFilename = cell.getBuildFileName();
              File parent = file;
              while (parent.toString().length() >= cellRootLength) {
                File buckFile = new File(parent, buildFilename);
                if (buckFile.isFile()) {
                  return Optional.ofNullable(buckFile);
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
              int cellRootLength = cell.getRoot().length();
              String buildFilename = cell.getBuildFileName();
              VirtualFile parent = file;
              while (parent.toString().length() >= cellRootLength) {
                VirtualFile buckFile = parent.findChild(buildFilename);
                if (buckFile != null && buckFile.exists() && !buckFile.isDirectory()) {
                  return Optional.ofNullable(buckFile);
                }
                parent = parent.getParent();
              }
              return Optional.empty();
            });
  }

  /** Finds the Buck file for the given target, starting from the given sourceFile. */
  public Optional<VirtualFile> findBuckTargetFile(VirtualFile sourceFile, String target) {
    Pattern pattern = Pattern.compile("^([^/]*)//([^:]*):[\\s\\S]*$");
    Matcher matcher = pattern.matcher(target);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String cellName = matcher.group(1);
    String pathFromCellRoot = matcher.group(2);

    return findBuckCell(sourceFile)
        .flatMap(
            cell -> {
              if ("".equals(cellName)) {
                return Optional.of(cell);
              } else {
                return projectSettingsProvider
                    .getCells()
                    .stream()
                    .filter(c -> c.getName().equals(cellName))
                    .findFirst();
              }
            })
        .flatMap(
            cell -> {
              return Optional.ofNullable(sourceFile.getFileSystem().findFileByPath(cell.getRoot()))
                  .map(cellRoot -> cellRoot.findFileByRelativePath(pathFromCellRoot))
                  .map(subDir -> subDir.findFileByRelativePath(cell.getBuildFileName()))
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
    Pattern pattern = Pattern.compile("^@?([^/]*)//([^:]*):([^:/]+)$");
    Matcher matcher = pattern.matcher(target);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String cellName = matcher.group(1);
    String pathFromCellRoot = matcher.group(2);
    String extensionFilename = matcher.group(3);

    return findBuckCell(sourceFile)
        .flatMap(
            cell -> {
              if ("".equals(cellName)) {
                return Optional.of(cell);
              } else {
                return projectSettingsProvider
                    .getCells()
                    .stream()
                    .filter(c -> c.getName().equals(cellName))
                    .findFirst();
              }
            })
        .flatMap(
            cell -> {
              return Optional.ofNullable(sourceFile.getFileSystem().findFileByPath(cell.getRoot()))
                  .map(cellRoot -> cellRoot.findFileByRelativePath(pathFromCellRoot))
                  .map(subDir -> subDir.findFileByRelativePath(extensionFilename))
                  .filter(VirtualFile::exists);
            });
  }
}
