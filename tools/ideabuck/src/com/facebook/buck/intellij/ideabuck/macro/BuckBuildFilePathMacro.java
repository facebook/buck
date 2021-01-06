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
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * Returns the full path to the nearest ancestral buck build file for the active selection, within
 * the selection's cell.
 */
public class BuckBuildFilePathMacro extends AbstractBuckMacro {
  @Override
  public String getName() {
    return "BuckBuildFilePath";
  }

  @Override
  public String getDescription() {
    return "The absolute path to the Buck build file for the active selection";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return getSelection(dataContext)
        .flatMap(
            selection ->
                getBuckTargetLocator(dataContext)
                    .flatMap(locator -> locator.findBuckFileForVirtualFile(selection))
                    .map(VirtualFile::getPath))
        .orElse(null);
  }
}
