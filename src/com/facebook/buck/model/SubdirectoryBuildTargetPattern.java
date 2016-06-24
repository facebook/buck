/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.model;

import com.facebook.buck.io.MorePaths;
import com.google.common.base.Objects;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A pattern matches build targets that have the specified ancestor directory.
 */
public class SubdirectoryBuildTargetPattern implements BuildTargetPattern {

  private final Path cellPath;
  private final Path pathWithinCell;

  /**
   * @param pathWithinCell The base path of the build target in the ancestor directory. It is
   *     expected to match the value returned from a {@link BuildTarget#getBasePath()}
   *     call.
   */
  public SubdirectoryBuildTargetPattern(Path cellPath, Path pathWithinCell) {
    this.cellPath = cellPath;
    this.pathWithinCell = pathWithinCell;
  }

  /**
   *
   * @return true if target not null and is under the directory basePathWithSlash,
   *         otherwise return false.
   */
  @Override
  public boolean apply(@Nullable BuildTarget target) {
    if (target == null) {
      return false;
    }

    if (!cellPath.equals(target.getCellPath())) {
      return false;
    }

    if (target.getBasePath().startsWith(pathWithinCell)) {
      return true;
    }

    // If the pathWithinCell is empty, we match the top level directory in the cell. _Everything_ is
    // a subdirectory of that.
    return "".equals(pathWithinCell.toString());
  }

  @Override
  public String getCellFreeRepresentation() {
    return "//" + MorePaths.pathWithUnixSeparators(pathWithinCell) + "/...";
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SubdirectoryBuildTargetPattern)) {
      return false;
    }
    SubdirectoryBuildTargetPattern that = (SubdirectoryBuildTargetPattern) o;
    return Objects.equal(this.cellPath, that.cellPath) &&
        Objects.equal(this.pathWithinCell, that.pathWithinCell);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(cellPath, pathWithinCell);
  }

  @Override
  public String toString() {
    return cellPath.getFileName().toString() + "//" + pathWithinCell.toString() + "/...";
  }

}
