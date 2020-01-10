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

import com.intellij.openapi.actionSystem.DataContext;
import org.jetbrains.annotations.Nullable;

/** Macro that expands to a cell name. */
public class BuckCellNameMacro extends AbstractBuckMacro {

  @Override
  public String getName() {
    return "BuckCellName";
  }

  @Override
  public String getDescription() {
    return "Unparameterized, expands to the name of the cell containing the selection."
        + " Parameterized, expands to the cell with the given name (only if it exists).";
  }

  /** Unparameterized, expands to the name of the cell containing the active selection. */
  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getSelectedCell(dataContext).map(cell -> cell.getName().orElse("")).orElse(null);
  }

  /**
   * Parameterized, expands the default cell (for no args) or the given cell name, but <em>only if
   * these cells exist</em>. Therefore, this macro can be used to verify that the user's ideabuck
   * configuration includes knowledge of a cell of a given name.
   */
  @Nullable
  @Override
  public String expand(DataContext dataContext, String... args) {
    return getCellFromParameters(dataContext, args)
        .map(cell -> cell.getName().orElse(""))
        .orElse(null);
  }
}
