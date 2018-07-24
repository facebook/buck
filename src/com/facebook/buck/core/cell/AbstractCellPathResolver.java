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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Contains base logic for {@link CellPathResolver}. */
public abstract class AbstractCellPathResolver implements CellPathResolver {

  /** @return sorted set of known roots in reverse natural order */
  @Override
  public ImmutableSortedSet<Path> getKnownRoots() {
    return ImmutableSortedSet.<Path>reverseOrder()
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
                    cellName
                        .map(
                            name -> {
                              List<String> suggestions =
                                  MoreStrings.getSpellingSuggestions(
                                      name, getCellPaths().keySet(), 2);
                              if (suggestions.isEmpty()) {
                                suggestions =
                                    getCellPaths()
                                        .keySet()
                                        .stream()
                                        .sorted()
                                        .collect(Collectors.toList());
                              }
                              return String.format(
                                  "Unknown cell: %s. Did you mean one of %s instead?",
                                  name,
                                  suggestions.isEmpty() ? getCellPaths().keySet() : suggestions);
                            })
                        .orElse("Cannot determine path of the root cell")));
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
