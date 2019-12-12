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
import com.intellij.openapi.actionSystem.DataContext;
import org.jetbrains.annotations.Nullable;

/**
 * Expands to the relative path from a cell root to the selection.
 *
 * <p>Example: for {@code foo//bar/baz:qux/file.txt}, returns {@code bar/baz/qux/file.txt}.
 */
public class BuckCellRelativeFilePathMacro extends AbstractBuckMacro {

  @Override
  public String getName() {
    return "BuckCellRelativeFilePath";
  }

  @Override
  public String getDescription() {
    return "Relative path from the root of the cell to the selection."
        + " When parameterized, returns the relative path from the root"
        + " of the *named* cell to the selection.";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getSelectedCell(dataContext)
                    .flatMap(BuckCellManager.Cell::getRootDirectory)
                    .map(root -> getRelativePath(root, selection)))
        .orElse(null);
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext, String[] args) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getCellFromParameters(dataContext, args)
                    .flatMap(BuckCellManager.Cell::getRootDirectory)
                    .map(root -> getRelativePath(root, selection)))
        .orElse(null);
  }
}
