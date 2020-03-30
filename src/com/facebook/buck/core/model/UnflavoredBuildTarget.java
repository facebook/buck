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

package com.facebook.buck.core.model;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.Objects;

/**
 * A build target in the form of
 *
 * <pre>cell//path:rule</pre>
 */
public class UnflavoredBuildTarget
    implements Comparable<UnflavoredBuildTarget>, DependencyStack.Element {
  private final CellRelativePath cellRelativeBasePath;
  private final String localName;
  private final int hash;

  private UnflavoredBuildTarget(CellRelativePath cellRelativeBasePath, String localName) {
    Preconditions.checkArgument(
        !localName.contains("#"), "Build target name cannot contain '#' but was: %s.", localName);
    this.cellRelativeBasePath = cellRelativeBasePath;
    this.localName = localName;
    this.hash = Objects.hash(cellRelativeBasePath, localName);
  }

  public String getLocalName() {
    return localName;
  }

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    UnflavoredBuildTarget that = (UnflavoredBuildTarget) o;
    return hash == that.hash
        && cellRelativeBasePath.equals(that.cellRelativeBasePath)
        && localName.equals(that.localName);
  }

  @Override
  public int compareTo(UnflavoredBuildTarget o) {
    return ComparisonChain.start()
        .compare(this.cellRelativeBasePath, o.cellRelativeBasePath)
        .compare(this.localName, o.localName)
        .result();
  }

  private static final Interner<UnflavoredBuildTarget> interner = Interners.newWeakInterner();

  /** A constructor. */
  public static UnflavoredBuildTarget of(CellRelativePath cellRelativeBasePath, String shortName) {
    return interner.intern(new UnflavoredBuildTarget(cellRelativeBasePath, shortName));
  }

  /** A constructor. */
  public static UnflavoredBuildTarget of(
      CanonicalCellName cell, BaseName baseName, String localName) {
    return of(CellRelativePath.of(cell, baseName.getPath()), localName);
  }

  public CanonicalCellName getCell() {
    return cellRelativeBasePath.getCellName();
  }

  public BaseName getBaseName() {
    return BaseName.ofPath(cellRelativeBasePath.getPath());
  }

  public String getFullyQualifiedName() {
    return cellRelativeBasePath + ":" + localName;
  }

  public CellRelativePath getCellRelativeBasePath() {
    return cellRelativeBasePath;
  }
}
