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
import com.google.common.base.Joiner;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractBuildTarget implements BuildTarget {

  @Override
  public abstract UnconfiguredBuildTargetView getUnconfiguredBuildTargetView();

  @Override
  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return getUnconfiguredBuildTargetView().getUnflavoredBuildTarget();
  }

  @Override
  public ImmutableSortedSet<Flavor> getFlavors() {
    return getUnconfiguredBuildTargetView().getFlavors();
  }

  @Override
  public CanonicalCellName getCell() {
    return getUnconfiguredBuildTargetView().getCell();
  }

  @Override
  public BaseName getBaseName() {
    return getUnconfiguredBuildTargetView().getBaseName();
  }

  @Override
  public CellRelativePath getCellRelativeBasePath() {
    return getUnconfiguredBuildTargetView().getCellRelativeBasePath();
  }

  @Override
  public String getShortName() {
    return getUnconfiguredBuildTargetView().getShortName();
  }

  @Override
  public String getShortNameAndFlavorPostfix() {
    return getUnconfiguredBuildTargetView().getShortNameAndFlavorPostfix();
  }

  @Override
  public String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  protected String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
  }

  @Override
  public String getFullyQualifiedName() {
    return getUnconfiguredBuildTargetView().getFullyQualifiedName();
  }

  @Override
  public String getCellRelativeName() {
    return getUnconfiguredBuildTargetView().getCellRelativeName();
  }

  @Override
  public boolean isFlavored() {
    return getUnconfiguredBuildTargetView().isFlavored();
  }

  @Override
  public BuildTarget assertUnflavored() {
    getUnconfiguredBuildTargetView().assertUnflavored();
    return this;
  }

  @Override
  public int compareTo(BuildTarget that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(this.getUnconfiguredBuildTargetView(), that.getUnconfiguredBuildTargetView())
        .compare(this.getTargetConfiguration(), that.getTargetConfiguration())
        .result();
  }
}
