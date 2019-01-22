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

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionCall;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiManager;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Canonical implementation of {@link BuckTargetLocator}. */
public class BuckTargetLocatorImpl implements BuckTargetLocator {

  private VirtualFileManager mVirtualFileManager;
  private PsiManager mPsiManager;
  private BuckCellManager mBuckCellManager;

  public BuckTargetLocatorImpl(
      VirtualFileManager virtualFileManager,
      PsiManager psiManager,
      BuckCellManager buckCellManager) {
    mVirtualFileManager = virtualFileManager;
    mPsiManager = psiManager;
    mBuckCellManager = buckCellManager;
  }

  private Optional<? extends Cell> findCellNamed(Optional<String> cellName) {
    if (cellName.isPresent()) {
      return mBuckCellManager.findCellByName(cellName.get());
    } else {
      return mBuckCellManager.getDefaultCell();
    }
  }

  private static Optional<Path> resolve(Path root, @Nullable String relativePath) {
    if (relativePath == null) {
      return Optional.empty();
    } else if (relativePath.isEmpty()) {
      return Optional.of(root);
    } else {
      return Optional.of(root.resolve(relativePath));
    }
  }

  private static Optional<VirtualFile> resolve(
      @Nullable VirtualFile root, @Nullable String relativePath) {
    if (root == null || relativePath == null) {
      return Optional.empty();
    } else if (relativePath.isEmpty()) {
      return Optional.of(root);
    } else {
      return Optional.ofNullable(root.findFileByRelativePath(relativePath));
    }
  }

  @Override
  public Optional<Path> findPathForTarget(BuckTarget buckTarget) {
    return findCellNamed(buckTarget.getCellName())
        .flatMap(
            cell ->
                resolve(cell.getRootPath(), buckTarget.getCellPath().orElse(null))
                    .map(dir -> dir.resolve(cell.getBuildfileName())));
  }

  @Override
  public Optional<VirtualFile> findVirtualFileForTarget(BuckTarget buckTarget) {
    return findCellNamed(buckTarget.getCellName())
        .flatMap(
            cell ->
                resolve(cell.getRootDirectory().orElse(null), buckTarget.getCellPath().orElse(null))
                    .map(dir -> dir.findChild(cell.getBuildfileName())));
  }

  @Override
  public Optional<Path> findPathForExtensionFile(BuckTarget buckTarget) {
    return findCellNamed(buckTarget.getCellName())
        .flatMap(
            cell ->
                resolve(cell.getRootPath(), buckTarget.getCellPath().orElse(null))
                    .map(dir -> dir.resolve(buckTarget.getRuleName())));
  }

  @Override
  public Optional<VirtualFile> findVirtualFileForExtensionFile(BuckTarget buckTarget) {
    return findCellNamed(buckTarget.getCellName())
        .flatMap(
            cell ->
                resolve(cell.getRootDirectory().orElse(null), buckTarget.getCellPath().orElse(null))
                    .map(dir -> dir.findFileByRelativePath(buckTarget.getRuleName())));
  }

  @Override
  public Optional<Path> findPathForTargetPattern(BuckTargetPattern buckTargetPattern) {
    return findCellNamed(buckTargetPattern.getCellName())
        .flatMap(
            cell ->
                resolve(cell.getRootPath(), buckTargetPattern.getCellPath().orElse(null))
                    .map(
                        path -> {
                          if (buckTargetPattern.getRuleName().isPresent()
                              || buckTargetPattern.isPackageMatching()) {
                            // pattern refers to one or more targets inside a specific BUCK file
                            return path.resolve(cell.getBuildfileName());
                          } else {
                            // pattern refers to a directory
                            return path;
                          }
                        }));
  }

  @Override
  public Optional<VirtualFile> findVirtualFileForTargetPattern(
      BuckTargetPattern buckTargetPattern) {
    return findCellNamed(buckTargetPattern.getCellName())
        .flatMap(
            cell ->
                resolve(
                        cell.getRootDirectory().orElse(null),
                        buckTargetPattern.getCellPath().orElse(null))
                    .map(
                        path -> {
                          if (buckTargetPattern.getRuleName().isPresent()
                              || buckTargetPattern.isPackageMatching()) {
                            // pattern refers to one or more targets inside a specific BUCK file
                            return path.findFileByRelativePath(cell.getBuildfileName());
                          } else {
                            // pattern refers to a directory
                            return path;
                          }
                        }));
  }

  @Override
  public Optional<BuckFunctionCall> findElementForTarget(BuckTarget buckTarget) {
    return findVirtualFileForTarget(buckTarget)
        .map(mPsiManager::findFile)
        .map(psiFile -> BuckPsiUtils.findTargetInPsiTree(psiFile, buckTarget.getRuleName()));
  }

  @Override
  public Optional<VirtualFile> findBuckFileForVirtualFile(VirtualFile file) {
    return mBuckCellManager
        .findCellByVirtualFile(file)
        .flatMap(
            cell ->
                cell.getRootDirectory()
                    .map(VirtualFile::getCanonicalPath)
                    .map(
                        cellRoot -> {
                          VirtualFile packageDir = file.isDirectory() ? file : file.getParent();
                          while (true) {
                            if (packageDir == null) {
                              return null;
                            }
                            String canonicalPackageDir = packageDir.getCanonicalPath();
                            if (canonicalPackageDir == null
                                || canonicalPackageDir.length() < cellRoot.length()) {
                              return null;
                            }
                            VirtualFile buckFile = packageDir.findChild(cell.getBuildfileName());
                            if (buckFile != null && buckFile.exists() && !buckFile.isDirectory()) {
                              return buckFile;
                            }
                            packageDir = packageDir.getParent();
                          }
                        }));
  }

  @Override
  public Optional<Path> findBuckFileForPath(Path path) {
    return mBuckCellManager
        .findCellByPath(path)
        .map(
            cell -> {
              Path cellRoot = cell.getRootPath();
              Path packageDir = path.toFile().isDirectory() ? path : path.getParent();
              while (true) {
                if (packageDir == null) {
                  return null;
                }
                if (packageDir.getNameCount() < cellRoot.getNameCount()) {
                  return null;
                }
                Path buckPath = packageDir.resolve(cell.getBuildfileName());
                File buckFile = buckPath.toFile();
                if (buckFile.exists() && buckFile.isFile()) {
                  return buckPath;
                }
                packageDir = packageDir.getParent();
              }
            });
  }

  @Override
  public BuckTarget resolve(BuckTarget target) {
    if (target.isAbsolute()) {
      return target;
    }

    @Nullable
    String defaultCellName = mBuckCellManager.getDefaultCell().flatMap(Cell::getName).orElse(null);
    return BuckTargetPattern.forCellName(defaultCellName).resolve(target);
  }

  @Override
  public Optional<BuckTarget> resolve(Path sourceFile, BuckTarget target) {
    if (target.isAbsolute()) {
      return Optional.of(target);
    } else {
      return findTargetPatternForPath(sourceFile)
          .flatMap(BuckTargetPattern::flatten)
          .map(base -> base.resolve(target));
    }
  }

  @Override
  public Optional<BuckTarget> resolve(VirtualFile sourceFile, BuckTarget target) {
    if (target.isAbsolute()) {
      return Optional.of(target);
    } else {
      return findTargetPatternForVirtualFile(sourceFile)
          .flatMap(BuckTargetPattern::flatten)
          .map(base -> base.resolve(target));
    }
  }

  @Override
  public BuckTargetPattern resolve(BuckTargetPattern pattern) {
    if (pattern.isAbsolute()) {
      return pattern;
    }
    @Nullable
    String defaultCellName = mBuckCellManager.getDefaultCell().flatMap(Cell::getName).orElse(null);
    return BuckTargetPattern.forCellName(defaultCellName).resolve(pattern);
  }

  @Override
  public Optional<BuckTargetPattern> resolve(Path sourceFile, BuckTargetPattern pattern) {
    if (pattern.isAbsolute()) {
      return Optional.of(pattern);
    } else {
      return findTargetPatternForPath(sourceFile)
          .flatMap(BuckTargetPattern::flatten)
          .map(base -> base.resolve(pattern));
    }
  }

  @Override
  public Optional<BuckTargetPattern> resolve(VirtualFile sourceFile, BuckTargetPattern pattern) {
    if (pattern.isAbsolute()) {
      return Optional.of(pattern);
    } else {
      return findTargetPatternForVirtualFile(sourceFile)
          .flatMap(BuckTargetPattern::flatten)
          .map(base -> base.resolve(pattern));
    }
  }

  @Override
  public Optional<BuckTargetPattern> findTargetPatternForVirtualFile(VirtualFile virtualFile) {
    return mBuckCellManager
        .findCellByVirtualFile(virtualFile)
        .flatMap(
            cell ->
                cell.getRootDirectory()
                    .map(
                        cellRoot -> {
                          String buildFileName = cell.getBuildfileName();
                          @Nullable
                          String pathToPackage; // The part between "//" and ":" in the pattern
                          @Nullable String rulePiece; // The part after the ":" in the pattern
                          VirtualFile packageDir; // Eventually, Buck root or nearest Buck file
                          if (virtualFile.isDirectory()) {
                            rulePiece = null;
                            packageDir = virtualFile;
                          } else if (buildFileName.equals(virtualFile.getName())) {
                            rulePiece = "";
                            packageDir = virtualFile.getParent();
                          } else {
                            rulePiece = virtualFile.getName();
                            packageDir = virtualFile.getParent();
                          }
                          while (packageDir != null && !packageDir.equals(cellRoot)) {
                            VirtualFile buildFile = packageDir.findChild(buildFileName);
                            if (buildFile != null && !buildFile.isDirectory()) {
                              break;
                            }
                            if (rulePiece == null) {
                              rulePiece = "";
                            }
                            rulePiece = packageDir.getName() + "/" + rulePiece;
                            packageDir = packageDir.getParent();
                          }
                          if (packageDir == null) {
                            return null;
                          }
                          if (packageDir.equals(cellRoot)) {
                            pathToPackage = null;
                          } else {
                            pathToPackage = packageDir.getName();
                            packageDir = packageDir.getParent();
                            while (packageDir != null && !packageDir.equals(cellRoot)) {
                              pathToPackage = packageDir.getName() + "/" + pathToPackage;
                              packageDir = packageDir.getParent();
                            }
                          }
                          if (packageDir == null) {
                            return null;
                          }
                          StringBuilder result = new StringBuilder();
                          cell.getName().ifPresent(result::append);
                          result.append("//");
                          if (pathToPackage != null) {
                            result.append(pathToPackage);
                            if (rulePiece == null) {
                              result.append("/"); // Add trailing slash to directory
                            }
                          }
                          if (rulePiece != null) {
                            result.append(":").append(rulePiece);
                          }
                          return result.toString();
                        }))
        .flatMap(BuckTargetPattern::parse);
  }

  @Override
  public Optional<BuckTargetPattern> findTargetPatternForPath(Path path) {
    return mBuckCellManager
        .findCellByPath(path)
        .map(
            cell -> {
              Path cellRoot = cell.getRootPath();
              String buildFileName = cell.getBuildfileName();
              @Nullable String pathToPackage; // The part between "//" and ":" in the pattern
              @Nullable String rulePiece; // The part after the ":" in the pattern, or null
              Path packageDir; // Eventually, Buck root or nearest Buck file
              if (path.toFile().isDirectory()) {
                rulePiece = null;
                packageDir = path;
              } else if (buildFileName.equals(path.getFileName().toString())) {
                rulePiece = "";
                packageDir = path.getParent();
              } else {
                rulePiece = path.getFileName().toString();
                packageDir = path.getParent();
              }
              while (packageDir != null && !packageDir.equals(cellRoot)) {
                if (packageDir.resolve(buildFileName).toFile().isFile()) {
                  break;
                }
                if (rulePiece == null) {
                  rulePiece = "";
                }
                rulePiece = packageDir.getFileName().toString() + "/" + rulePiece;
                packageDir = packageDir.getParent();
              }
              if (packageDir == null) {
                return null;
              }
              if (packageDir.equals(cellRoot)) {
                pathToPackage = null;
              } else {
                pathToPackage = packageDir.getFileName().toString();
                packageDir = packageDir.getParent();
                while (packageDir != null && !packageDir.equals(cellRoot)) {
                  pathToPackage = packageDir.getFileName().toString() + "/" + pathToPackage;
                  packageDir = packageDir.getParent();
                }
              }
              if (packageDir == null) {
                return null;
              }
              StringBuilder result = new StringBuilder();
              cell.getName().ifPresent(result::append);
              result.append("//");
              if (pathToPackage != null) {
                result.append(pathToPackage);
                if (rulePiece == null) {
                  result.append("/"); // Add trailing slash to directory
                }
              }
              if (rulePiece != null) {
                result.append(":").append(rulePiece);
              }
              return result.toString();
            })
        .flatMap(BuckTargetPattern::parse);
  }
}
