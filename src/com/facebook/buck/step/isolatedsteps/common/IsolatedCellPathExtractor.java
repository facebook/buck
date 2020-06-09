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

package com.facebook.buck.step.isolatedsteps.common;

import com.facebook.buck.core.cell.CellPathExtractor;
import com.facebook.buck.core.cell.exception.UnknownCellException;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** {@link CellPathExtractor} implementation that is isolated from the build graph. */
@BuckStyleValue
public abstract class IsolatedCellPathExtractor implements CellPathExtractor {

  public abstract AbsPath getRoot();

  public abstract ImmutableMap<String, RelPath> getCellToPathMapping();

  @Override
  public final AbsPath getCellPathOrThrow(CanonicalCellName cellName) {
    return getCellPath(cellName)
        .orElseThrow(
            () ->
                new UnknownCellException(
                    cellName.getLegacyName(), getCellToPathMapping().keySet()));
  }

  @Override
  public final Optional<AbsPath> getCellPath(CanonicalCellName cellName) {
    return cellName
        .getLegacyName()
        .map(name -> getCellToPathMapping().get(name))
        .map(relPath -> Optional.ofNullable(getRoot().resolve(relPath)))
        .orElseGet(() -> Optional.of(getRoot()));
  }

  public static IsolatedCellPathExtractor of(
      AbsPath root, ImmutableMap<String, RelPath> cellToPathMapping) {
    return ImmutableIsolatedCellPathExtractor.ofImpl(root, cellToPathMapping);
  }
}
