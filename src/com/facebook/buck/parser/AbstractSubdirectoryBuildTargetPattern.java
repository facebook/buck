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
package com.facebook.buck.parser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.file.MorePaths;
import java.nio.file.Path;
import org.immutables.value.Value;

/** A pattern matches build targets that have the specified ancestor directory. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleTuple
abstract class AbstractSubdirectoryBuildTargetPattern implements BuildTargetPattern {

  protected abstract Path getCellPath();

  /**
   * Base path of the build target in the ancestor directory. It is expected to match the value
   * returned from a {@link BuildTarget#getBasePath()} call.
   */
  protected abstract Path getPathWithinCell();

  /**
   * @return true if target not null and is under the directory basePathWithSlash, otherwise return
   *     false.
   */
  @Override
  public boolean matches(BuildTarget target) {
    if (!getCellPath().equals(target.getCellPath())) {
      return false;
    }

    if (target.getBasePath().startsWith(getPathWithinCell())) {
      return true;
    }

    // If the getPathWithinCell() is empty, we match the top level directory in the cell.
    // _Everything_ is a subdirectory of that.
    return "".equals(getPathWithinCell().toString());
  }

  @Override
  public String getCellFreeRepresentation() {
    return "//" + MorePaths.pathWithUnixSeparators(getPathWithinCell()) + "/...";
  }

  @Override
  public String toString() {
    return getCellPath().getFileName() + "//" + getPathWithinCell() + "/...";
  }
}
