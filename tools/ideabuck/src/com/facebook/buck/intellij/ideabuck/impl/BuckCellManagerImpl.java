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
import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Default implementation of {@link BuckCellManager}. */
public class BuckCellManagerImpl implements BuckCellManager {

  private static final Logger LOGGER = Logger.getInstance(BuckCellManagerImpl.class);

  private BuckCellSettingsProvider mBuckCellSettingsProvider;
  private VirtualFileManager mVirtualFileManager;
  private PathMacroManager mPathMacroManager;

  public static BuckCellManagerImpl getInstance(Project project) {
    return project.getComponent(BuckCellManagerImpl.class);
  }

  public BuckCellManagerImpl(
      BuckCellSettingsProvider buckCellSettingsProvider,
      VirtualFileManager virtualFileManager,
      PathMacroManager pathMacroManager) {
    mBuckCellSettingsProvider = buckCellSettingsProvider;
    mVirtualFileManager = virtualFileManager;
    mPathMacroManager = pathMacroManager;
  }

  @Override
  public Optional<CellImpl> getDefaultCell() {
    return mBuckCellSettingsProvider.getDefaultCell().map(CellImpl::new);
  }

  @Override
  public List<CellImpl> getCells() {
    return mBuckCellSettingsProvider
        .getCells()
        .stream()
        .map(CellImpl::new)
        .collect(Collectors.toList());
  }

  @Override
  public Optional<CellImpl> findCellByName(String name) {
    return mBuckCellSettingsProvider
        .getCells()
        .stream()
        .filter(cell -> name.equals(cell.getName()))
        .findFirst()
        .map(CellImpl::new);
  }

  @Override
  public Optional<CellImpl> findCellByVirtualFile(VirtualFile virtualFile) {
    return findCellByCanonicalPath(virtualFile.getCanonicalPath());
  }

  @Override
  public Optional<CellImpl> findCellByPath(Path path) {
    try {
      return findCellByCanonicalPath(path.toFile().getCanonicalPath());
    } catch (IOException e) {
      LOGGER.error(e);
      return Optional.empty();
    }
  }

  private Optional<CellImpl> findCellByCanonicalPath(String canonicalPath) {
    if (canonicalPath == null) {
      return Optional.empty();
    }
    return mBuckCellSettingsProvider
        .getCells()
        .stream()
        .filter(
            cell -> {
              String root = mPathMacroManager.expandPath(cell.getRoot());
              return canonicalPath.equals(root) || canonicalPath.startsWith(root + File.separator);
            })
        .max(Ordering.natural().onResultOf(cell -> cell.getRoot().length()))
        .map(CellImpl::new);
  }

  /** Default implementation of {@link BuckCellManager.Cell}. */
  class CellImpl implements BuckCellManager.Cell {
    private BuckCell buckCell;

    private CellImpl(BuckCell cell) {
      buckCell = cell;
    }

    @VisibleForTesting
    BuckCell getBuckCell() {
      return buckCell;
    }

    @Override
    public Optional<String> getName() {
      String name = buckCell.getName();
      if ("".equals(name)) {
        return Optional.empty();
      }
      return Optional.of(name);
    }

    @Override
    public String getBuildfileName() {
      return buckCell.getBuildFileName();
    }

    @Override
    public Optional<VirtualFile> getRootDirectory() {
      String root = "file://" + getRoot();
      return Optional.ofNullable(mVirtualFileManager.findFileByUrl(root));
    }

    @Override
    public Path getRootPath() {
      return Paths.get(getRoot());
    }

    private String getRoot() {
      return mPathMacroManager.expandPath(buckCell.getRoot());
    }

    @Override
    public int hashCode() {
      return buckCell.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof CellImpl) && getBuckCell().equals(((CellImpl) obj).getBuckCell());
    }
  }
}
