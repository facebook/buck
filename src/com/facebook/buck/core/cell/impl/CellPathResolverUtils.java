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

package com.facebook.buck.core.cell.impl;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Utils methods for {@CellPathResolver} */
public class CellPathResolverUtils {

  private CellPathResolverUtils() {}

  /** Returns cell to relative path mapping */
  public static ImmutableMap<String, RelPath> getCellToMapMappings(
      AbsPath ruleCellRoot, CellPathResolver cellPathResolver) {
    return cellPathResolver.getCellPathsByRootCellExternalName().entrySet().stream()
        .collect(
            toImmutableMap(
                Map.Entry::getKey,
                e -> ProjectFilesystemUtils.relativize(ruleCellRoot, e.getValue().getPath())));
  }
}
