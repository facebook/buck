/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public final class FakeCellPathResolver implements CellPathResolver {
  @Nullable private final ProjectFilesystem projectFilesystem;
  private final ImmutableMap<String, Path> cellPaths;

  public FakeCellPathResolver(
      ProjectFilesystem projectFilesystem, ImmutableMap<String, Path> cellPaths) {
    this.projectFilesystem = projectFilesystem;
    this.cellPaths = cellPaths;
  }

  public FakeCellPathResolver(ImmutableMap<String, Path> cellPaths) {
    this(null, cellPaths);
  }

  public FakeCellPathResolver(ProjectFilesystem projectFilesystem) {
    this(projectFilesystem, ImmutableMap.of());
  }

  @Override
  public Path getCellPath(Optional<String> cellName) {
    if (cellName.isPresent()) {
      Path result = cellPaths.get(cellName.get());
      if (result == null) {
        throw new IllegalArgumentException("Cell not found: " + cellName);
      }
      return result;
    } else if (projectFilesystem != null) {
      return projectFilesystem.getRootPath();
    } else {
      throw new IllegalArgumentException("Root cell not defined.");
    }
  }

  @Override
  public ImmutableMap<String, Path> getCellPaths() {
    return cellPaths;
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    if (cellPath.equals(projectFilesystem.getRootPath())) {
      return Optional.empty();
    } else {
      return Optional.of(
          cellPaths.asMultimap().inverse().get(cellPath).stream().sorted().findFirst().get());
    }
  }
}
