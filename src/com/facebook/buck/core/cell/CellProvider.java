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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

/** Implementation of this interface holds all the cells. */
public interface CellProvider {

  Cell getCellByPath(AbsPath path);

  // TODO(cjhopman): Shouldn't this be based on CanonicalCellName instead?
  Cell getCellByPath(Path path);

  /** Get cell object by canonicall cell name */
  Cell getCellByCanonicalCellName(CanonicalCellName canonicalCellName);

  Cells getRootCell();

  ImmutableMap<AbsPath, Cell> getLoadedCells();
}
