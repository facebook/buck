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

import com.facebook.buck.core.cell.exception.UnknownCellException;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Implementation of {@link CellNameResolver} based on the known cells mapping. */
@BuckStyleValue
public abstract class DefaultCellNameResolver implements CellNameResolver {
  @Override
  public abstract ImmutableMap<Optional<String>, CanonicalCellName> getKnownCells();

  @Override
  public Optional<CanonicalCellName> getNameIfResolvable(Optional<String> localName) {
    return Optional.ofNullable(getKnownCells().get(localName));
  }

  @Override
  public CanonicalCellName getName(Optional<String> localName) {
    return getNameIfResolvable(localName)
        .orElseThrow(
            () ->
                new UnknownCellException(
                    localName,
                    getKnownCells().keySet().stream()
                        .flatMap(optional -> optional.map(Stream::of).orElse(Stream.empty()))
                        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()))));
  }

  public static ImmutableDefaultCellNameResolver of(
      Map<? extends Optional<String>, ? extends CanonicalCellName> knownCells) {
    return ImmutableDefaultCellNameResolver.of(knownCells);
  }
}
