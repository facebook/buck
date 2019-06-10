/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.macro;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Macro that expands to the cell name, e.g., for "foo//bar/baz:qux/file.txt", returns the
 * fully-qualified path to the root of the "foo" cell.
 */
public class BuckCellRootMacro extends AbstractBuckTargetPatternMacro {

  @Override
  public String getName() {
    return "BuckCellRoot";
  }

  @Override
  public String getDescription() {
    return "If no parameters are supplied and a file is selected,"
        + " the fully-qualified path to the cell root containing the selected file."
        + " If a parameter is supplied, the path to the root of the cell of the given name.";
  }

  @Nullable
  private String expandCellName(DataContext dataContext, String cellName) {
    Project project = CommonDataKeys.PROJECT.getData(dataContext);
    if (project == null || project.isDefault()) {
      return null;
    }
    BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
    Optional<? extends BuckCellManager.Cell> cell;
    if ("".equals(cellName)) {
      cell = buckCellManager.getDefaultCell();
    } else {
      cell = buckCellManager.findCellByName(cellName);
    }
    return cell.map(BuckCellManager.Cell::getRootPath)
        .map(Path::toAbsolutePath)
        .map(Path::toString)
        .orElse(null);
  }

  /** With no arguments, expands to the cell root of the active selection. */
  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return expandToTargetPattern(dataContext)
        .map(pattern -> pattern.getCellName().orElse(""))
        .map(cellName -> expandCellName(dataContext, cellName))
        .orElse(null);
  }

  /** With an argument, expands to the cell root of the named cell. */
  @Nullable
  @Override
  public String expand(DataContext dataContext, String... args) {
    String cellName = (args.length == 0) ? expand(dataContext) : args[0];
    return expandCellName(dataContext, cellName);
  }
}
