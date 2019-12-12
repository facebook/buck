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

/** Expands to the buildfile name of the selected or named cell. */
public class BuckBuildFileNameMacro extends AbstractBuckMacro {
  @Override
  public String getDescription() {
    return "If no parameters are supplied and a file is selected,"
        + " returns the buildfile name of the cell containing the selection."
        + " If a parameter is supplied, returns the buildfile name of the named cell.";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getSelectedCell(dataContext).map(BuckCellManager.Cell::getBuildfileName).orElse(null);
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext, String... args) {
    return getCellFromParameters(dataContext, args)
        .map(BuckCellManager.Cell::getBuildfileName)
        .orElse(null);
  }
}
