/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.AbstractUnflavoredBuildTargetView;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.primitives.Booleans;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Immutable implementation of {@link UnflavoredBuildTargetView} */
public class ImmutableUnflavoredBuildTargetView extends AbstractUnflavoredBuildTargetView {

  private final UnconfiguredBuildTarget data;
  private final Path cellPath;
  private final int hash;

  private ImmutableUnflavoredBuildTargetView(Path cellPath, UnconfiguredBuildTarget data) {
    this.data = data;
    this.cellPath = cellPath;

    // always precompute hash because we intern object anyways
    hash = Objects.hash(cellPath, data);
  }

  /** Interner for instances of UnflavoredBuildTargetView. */
  private static final Interner<ImmutableUnflavoredBuildTargetView> interner =
      Interners.newWeakInterner();

  @Override
  public UnconfiguredBuildTarget getData() {
    return data;
  }

  @Override
  public Path getCellPath() {
    return cellPath;
  }

  @Override
  public Optional<String> getCell() {
    return data.getCell() == "" ? Optional.empty() : Optional.of(data.getCell());
  }

  @Override
  public String getBaseName() {
    return data.getBaseName();
  }

  @Override
  public String getShortName() {
    return data.getName();
  }

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  /**
   * Create new instance of {@link UnflavoredBuildTargetView}
   *
   * @param cellPath Absolute path to the cell root that owns this build target
   * @param cellName Name of the cell that owns this build target
   * @param baseName Base part of build target name, like "//some/target"
   * @param shortName Last part of build target name after colon
   */
  public static ImmutableUnflavoredBuildTargetView of(
      Path cellPath, Optional<String> cellName, String baseName, String shortName) {
    return of(
        cellPath,
        ImmutableUnconfiguredBuildTarget.of(
            cellName.orElse(""), baseName, shortName, UnconfiguredBuildTarget.NO_FLAVORS));
  }

  /**
   * Create new instance of {@link UnflavoredBuildTargetView}
   *
   * @param cellPath Absolute path to the cell root that owns this build target
   * @param data {@link UnconfiguredBuildTarget} which encapsulates build target data
   */
  public static ImmutableUnflavoredBuildTargetView of(Path cellPath, UnconfiguredBuildTarget data) {
    return interner.intern(new ImmutableUnflavoredBuildTargetView(cellPath, data));
  }

  @Override
  public int compareTo(UnflavoredBuildTargetView o) {
    if (this == o) {
      return 0;
    }
    int cmp = Booleans.compare(o.getCell().isPresent(), getCell().isPresent());
    if (cmp != 0) {
      return cmp;
    }
    if (getCell().isPresent() && o.getCell().isPresent()) {
      cmp = MoreStrings.compareStrings(getCell().get(), o.getCell().get());
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = MoreStrings.compareStrings(getBaseName(), o.getBaseName());
    if (cmp != 0) {
      return cmp;
    }
    return MoreStrings.compareStrings(getShortName(), o.getShortName());
  }

  @Override
  public boolean equals(Object another) {
    if (this == another) {
      return true;
    }
    return another instanceof ImmutableUnflavoredBuildTargetView
        && equalTo((ImmutableUnflavoredBuildTargetView) another);
  }

  private boolean equalTo(ImmutableUnflavoredBuildTargetView another) {
    if (hash != another.hash) {
      return false;
    }

    return cellPath.equals(another.cellPath) && data.equals(another.data);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
