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

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.ide.macro.Macro;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Optional;

/** Common base class for macros derived from the {@link BuckTargetPattern} of the active file. */
abstract class AbstractBuckTargetPatternMacro extends Macro {
  protected Optional<BuckTargetPattern> expandToTargetPattern(DataContext dataContext) {
    VirtualFile vFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
    if (vFile == null) {
      return Optional.empty();
    }
    Project project = CommonDataKeys.PROJECT.getData(dataContext);
    if (project == null || project.isDefault()) {
      return Optional.empty();
    }
    return BuckTargetLocator.getInstance(project).findTargetPatternForVirtualFile(vFile);
  }
}
