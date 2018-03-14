/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Contains base logic for {@link CellPathResolver}. */
public abstract class AbstractCellPathResolver implements CellPathResolver {

  @Override
  public ImmutableSet<Path> getKnownRoots() {
    return ImmutableSet.<Path>builder()
        .addAll(getCellPaths().values())
        .add(getCellPathOrThrow(Optional.empty()))
        .build();
  }

  @Override
  public Path getCellPathOrThrow(Optional<String> cellName) {
    return getCellPath(cellName)
        .orElseThrow(
            () ->
                new AssertionError(
                    String.format("Unknown cell: %s", cellName.orElse("<root cell>"))));
  }

  @Override
  public Path getCellPathOrThrow(BuildTarget buildTarget) {
    return getCellPathOrThrow(buildTarget.getCell());
  }

  @Override
  public Path getCellPathOrThrow(UnflavoredBuildTarget buildTarget) {
    return getCellPathOrThrow(buildTarget.getCell());
  }
}
