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

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

public final class TestCellPathResolver {
  public static CellPathResolver get(ProjectFilesystem projectFilesystem) {
    return create(projectFilesystem.getRootPath());
  }

  private TestCellPathResolver() {}

  /** Utility for creating a simple DefaultCellPathResolver from a root path and cell mappings. */
  public static DefaultCellPathResolver create(
      AbsPath rootPath, ImmutableMap<String, Path> cellMappings) {
    Config config =
        new Config(
            RawConfig.of(
                ImmutableMap.of(
                    DefaultCellPathResolver.REPOSITORIES_SECTION,
                    cellMappings.entrySet().stream()
                        .collect(
                            ImmutableMap.toImmutableMap(
                                e -> e.getKey(), e -> e.getValue().toString())))));
    return DefaultCellPathResolver.create(rootPath, config);
  }

  /** Utility for creating a simple DefaultCellPathResolver from a root path and cell mappings. */
  public static DefaultCellPathResolver create(
      Path rootPath, ImmutableMap<String, Path> cellMappings) {
    return create(AbsPath.of(rootPath), cellMappings);
  }

  /** Utility to create a DefaultCellPathResolver for a root path with no other cells. */
  public static DefaultCellPathResolver create(Path root) {
    return create(root, ImmutableMap.of());
  }

  /** Utility to create a DefaultCellPathResolver for a root path with no other cells. */
  public static DefaultCellPathResolver create(AbsPath root) {
    return create(root.getPath());
  }
}
