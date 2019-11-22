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
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.Objects;

/** Immutable implementation of {@link UnflavoredBuildTargetView} */
public class ImmutableUnflavoredBuildTargetView extends AbstractUnflavoredBuildTargetView {

  private final UnconfiguredBuildTarget data;
  private final int hash;

  private ImmutableUnflavoredBuildTargetView(UnconfiguredBuildTarget data) {
    Preconditions.checkArgument(data.getFlavors().isEmpty());
    this.data = data;
    this.hash = Objects.hash(data);
  }

  /** Interner for instances of UnflavoredBuildTargetView. */
  private static final Interner<ImmutableUnflavoredBuildTargetView> interner =
      Interners.newWeakInterner();

  @Override
  public UnconfiguredBuildTarget getData() {
    return data;
  }

  @Override
  public CanonicalCellName getCell() {
    return data.getCell();
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
   * @param cellName Name of the cell that owns this build target
   * @param baseName Base part of build target name, like "//some/target"
   * @param shortName Last part of build target name after colon
   */
  public static ImmutableUnflavoredBuildTargetView of(
      CanonicalCellName cellName, String baseName, String shortName) {
    return of(
        UnconfiguredBuildTarget.of(
            cellName, baseName, shortName, UnconfiguredBuildTarget.NO_FLAVORS));
  }

  /**
   * Create new instance of {@link UnflavoredBuildTargetView}
   *
   * @param data {@link UnconfiguredBuildTarget} which encapsulates build target data
   */
  public static ImmutableUnflavoredBuildTargetView of(UnconfiguredBuildTarget data) {
    return interner.intern(new ImmutableUnflavoredBuildTargetView(data));
  }

  @Override
  public int compareTo(UnflavoredBuildTargetView o) {
    if (this == o) {
      return 0;
    }

    int cmp = getCell().compareTo(o.getCell());
    if (cmp != 0) {
      return cmp;
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
    return hash == another.hash && data.equals(another.data);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
