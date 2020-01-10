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
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Macro that expands to the root of a buck cell. */
public class BuckCellRootDirMacro extends AbstractBuckMacro {

  @Override
  public String getName() {
    return "BuckCellRootDir";
  }

  @Override
  public String getDescription() {
    return "If no parameters are supplied and a file is selected,"
        + " the fully-qualified path to the cell root containing the selected file."
        + " If a parameter is supplied, the path to the root of the cell of the given name.";
  }

  @Nullable
  private String extractCellPath(BuckCellManager.Cell cell) {
    return Optional.of(cell)
        .map(BuckCellManager.Cell::getRootPath)
        .map(Path::toAbsolutePath)
        .map(Path::toString)
        .orElse(null);
  }

  /**
   * Unparameterized version expands to the path to the root of the cell containing the active
   * selection. e.g., if "foo//bar/baz:qux/file.txt", is selected, this returns the fully-qualified
   * path to the root of the "foo" cell.
   */
  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getSelectedCell(dataContext).map(this::extractCellPath).orElse(null);
  }

  /**
   * Parameterized version of this macro expands to the path to the root of the named cell (or the
   * default cell, if no parameter or the empty string are supplied).
   */
  @Nullable
  @Override
  public String expand(DataContext dataContext, String... args) {
    return getCellFromParameters(dataContext, args).map(this::extractCellPath).orElse(null);
  }
}
