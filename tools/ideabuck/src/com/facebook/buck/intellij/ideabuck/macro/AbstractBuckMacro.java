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

package com.facebook.buck.intellij.ideabuck.macro;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.ide.macro.Macro;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Common base class for buck macros. */
abstract class AbstractBuckMacro extends Macro {

  private final String name;

  @Override
  public String getName() {
    return name;
  }

  AbstractBuckMacro() {
    String suffix = "Macro";
    String className = this.getClass().getSimpleName();
    if (!className.endsWith(suffix)) {
      throw new AssertionError(
          className
              + " extends "
              + AbstractBuckMacro.class.getSimpleName()
              + ", and subclasses must have names ending in \""
              + suffix
              + "\"");
    }
    name = className.substring(0, className.length() - suffix.length());
  }

  /** Returns the active selection (file or directory). */
  protected Optional<VirtualFile> getSelection(DataContext dataContext) {
    return Optional.of(dataContext).map(CommonDataKeys.VIRTUAL_FILE::getData);
  }

  /** Returns the build file for the active selection (file or directory). */
  protected Optional<VirtualFile> getBuildFile(DataContext dataContext) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getBuckTargetLocator(dataContext)
                    .flatMap(locator -> locator.findBuckFileForVirtualFile(selection)));
  }

  /** Returns the active project. */
  protected Optional<Project> getProject(DataContext dataContext) {
    return Optional.of(dataContext)
        .map(CommonDataKeys.PROJECT::getData)
        .filter(project -> !project.isDefault());
  }

  /** Returns the BuckCellManager for the active project. */
  protected Optional<BuckCellManager> getBuckCellManager(DataContext dataContext) {
    return getProject(dataContext).map(BuckCellManager::getInstance);
  }

  /** Returns the BuckTargetLocator for the active project. */
  protected Optional<BuckTargetLocator> getBuckTargetLocator(DataContext dataContext) {
    return getProject(dataContext).map(BuckTargetLocator::getInstance);
  }

  /** Returns the {@link BuckCellManager.Cell} for the active selection. */
  protected Optional<? extends BuckCellManager.Cell> getSelectedCell(DataContext dataContext) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getBuckCellManager(dataContext)
                    .flatMap(bcm -> bcm.findCellByVirtualFile(selection)));
  }

  /** Returns the {@link BuckCellManager.Cell} for the given parameters. */
  protected Optional<? extends BuckCellManager.Cell> getCellFromParameters(
      DataContext dataContext, String[] args) {
    Optional<BuckCellManager> buckCellManager = getBuckCellManager(dataContext);
    if (args.length == 0 || "".equals(args[0])) {
      return buckCellManager.flatMap(BuckCellManager::getDefaultCell);
    } else {
      return buckCellManager.flatMap(bcm -> bcm.findCellByName(args[0]));
    }
  }

  /** Returns the target pattern associated with the active selection. */
  protected Optional<BuckTargetPattern> getTargetPattern(DataContext dataContext) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getBuckTargetLocator(dataContext)
                    .map(locator -> locator.findTargetPatternForVirtualFile(selection)))
        .orElse(null);
  }

  /** Returns the relative path from the base to the target. */
  @Nullable
  protected String getRelativePath(VirtualFile base, VirtualFile target) {
    return VfsUtilCore.findRelativePath(base, target, '/');
  }
}
