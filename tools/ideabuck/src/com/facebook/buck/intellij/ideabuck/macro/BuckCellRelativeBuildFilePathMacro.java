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
 * For a selection within a cell, expands to the path to its nearest ancestral build file, relative
 * to the cell root.
 *
 * <p>Example: for {@code foo//bar/baz:qux/file.txt}, returns {@code bar/baz/BUCK}.
 */
public class BuckCellRelativeBuildFilePathMacro extends AbstractBuckMacro {

  @Override
  public String getName() {
    return "BuckCellRelativeBuildFilePath";
  }

  @Override
  public String getDescription() {
    return "Relative path from the root of the cell to the build file for the selected file";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getBuildFile(dataContext)
        .flatMap(
            buildFile ->
                getSelectedCell(dataContext)
                    .flatMap(BuckCellManager.Cell::getRootDirectory)
                    .map(root -> getRelativePath(root, buildFile)))
        .orElse(null);
  }
}
