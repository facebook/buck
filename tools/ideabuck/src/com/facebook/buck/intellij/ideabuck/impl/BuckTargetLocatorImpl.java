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
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiManager;
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
}
