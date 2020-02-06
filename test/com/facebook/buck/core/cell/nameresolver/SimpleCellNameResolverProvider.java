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

package com.facebook.buck.core.cell.nameresolver;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public class SimpleCellNameResolverProvider implements CellNameResolverProvider {
  private final ImmutableList<String> cellNames;

  public SimpleCellNameResolverProvider(String... cellNames) {
    this.cellNames = ImmutableList.copyOf(cellNames);
  }

  public SimpleCellNameResolverProvider(CanonicalCellName... cellNames) {
    this.cellNames =
        Arrays.stream(cellNames)
            .flatMap(
                n -> {
                  return n.getLegacyName().isPresent()
                      ? Stream.of(n.getLegacyName().get())
                      : Stream.empty();
                })
            .distinct()
            .collect(ImmutableList.toImmutableList());
  }

  @Override
  public CellNameResolver cellNameResolverForCell(CanonicalCellName cellName) {
    if (cellName == CanonicalCellName.rootCell()) {
      return TestCellNameResolver.forRoot(cellNames.toArray(new String[0]));
    } else {
      return TestCellNameResolver.forSecondary(
          cellName.getName(), Optional.empty(), cellNames.toArray(new String[0]));
    }
  }
}
