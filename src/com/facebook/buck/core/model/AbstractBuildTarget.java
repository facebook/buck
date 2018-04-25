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
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Optional;

public abstract class AbstractBuildTarget implements BuildTarget {

  private static final Ordering<Iterable<Flavor>> LEXICOGRAPHICAL_ORDERING =
      Ordering.<Flavor>natural().lexicographical();

  @Override
  public Optional<String> getCell() {
    return getUnflavoredBuildTarget().getCell();
  }

  @Override
  public Path getCellPath() {
    return getUnflavoredBuildTarget().getCellPath();
  }

  @Override
  public String getBaseName() {
    return getUnflavoredBuildTarget().getBaseName();
  }

  @Override
  public Path getBasePath() {
    return getUnflavoredBuildTarget().getBasePath();
  }

  @Override
  public String getShortName() {
    return getUnflavoredBuildTarget().getShortName();
  }

  @Override
  public String getShortNameAndFlavorPostfix() {
    return getShortName() + getFlavorPostfix();
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
    return getUnflavoredBuildTarget().getFullyQualifiedName() + getFlavorPostfix();
  }

  @Override
  public boolean isFlavored() {
    return !(getFlavors().isEmpty());
  }

  @Override
  public UnflavoredBuildTarget checkUnflavored() {
    Preconditions.checkState(!isFlavored(), "%s is flavored.", this);
    return getUnflavoredBuildTarget();
  }

  @Override
  public int compareTo(BuildTarget o) {
    if (this == o) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(getUnflavoredBuildTarget(), o.getUnflavoredBuildTarget())
        .compare(getFlavors(), o.getFlavors(), LEXICOGRAPHICAL_ORDERING)
        .result();
  }
}
