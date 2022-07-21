/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.test;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.CellRelativePath;
import java.nio.file.Path;

/** Util class for test runnning. */
public class TestRunningUtils {

  /**
   * @param superRootPath the absolute path of the project root ie. root of all cells
   * @param cellPathResolver used to resolve the absolute path of cell relative path
   * @param cellRelativePath cell relative path of a package
   * @return project relative path of the package
   */
  public static Path getSuperProjectRelativePath(
      AbsPath superRootPath, CellPathResolver cellPathResolver, CellRelativePath cellRelativePath) {
    return superRootPath
        .relativize(cellPathResolver.resolveCellRelativePath(cellRelativePath))
        .getPath();
  }
}
