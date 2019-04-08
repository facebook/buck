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

package com.facebook.buck.core.model;

import com.google.common.base.Joiner;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public abstract class AbstractBuildTarget implements BuildTarget {

  @Override
  public abstract UnconfiguredBuildTargetView getUnconfiguredBuildTargetView();

  @Override
  public UnflavoredBuildTargetView getUnflavoredBuildTarget() {
    return getUnconfiguredBuildTargetView().getUnflavoredBuildTargetView();
  }

  @Override
  public ImmutableSortedSet<Flavor> getFlavors() {
    return getUnconfiguredBuildTargetView().getFlavors();
  }

  @Override
  public Optional<String> getCell() {
    return getUnconfiguredBuildTargetView().getCell();
  }

  @Override
  public Path getCellPath() {
    return getUnconfiguredBuildTargetView().getCellPath();
  }

  @Override
  public String getBaseName() {
    return getUnconfiguredBuildTargetView().getBaseName();
  }

  @Override
  public Path getBasePath() {
    return getUnconfiguredBuildTargetView().getBasePath();
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
  public boolean isFlavored() {
    return getUnconfiguredBuildTargetView().isFlavored();
  }

  @Override
  public BuildTarget assertUnflavored() {
    getUnconfiguredBuildTargetView().assertUnflavored();
    return this;
  }

  @Override
  public int compareTo(BuildTarget o) {
    if (this == o) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(getUnconfiguredBuildTargetView(), o.getUnconfiguredBuildTargetView())
        .result();
  }
}
