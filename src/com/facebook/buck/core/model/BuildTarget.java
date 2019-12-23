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
import com.google.common.base.Joiner;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public abstract class BuildTarget implements Comparable<BuildTarget>, DependencyStack.Element {

  public abstract UnconfiguredBuildTargetView getUnconfiguredBuildTargetView();

  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return getUnconfiguredBuildTargetView().getUnflavoredBuildTarget();
  }

  public ImmutableSortedSet<Flavor> getFlavors() {
    return getUnconfiguredBuildTargetView().getFlavors();
  }

  public abstract TargetConfiguration getTargetConfiguration();

  public CanonicalCellName getCell() {
    return getUnconfiguredBuildTargetView().getCell();
  }

  public BaseName getBaseName() {
    return getUnconfiguredBuildTargetView().getBaseName();
  }

  public CellRelativePath getCellRelativeBasePath() {
    return getUnconfiguredBuildTargetView().getCellRelativeBasePath();
  }

  public String getShortName() {
    return getUnconfiguredBuildTargetView().getShortName();
  }

  /**
   * If this build target were cell//third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  public String getShortNameAndFlavorPostfix() {
    return getUnconfiguredBuildTargetView().getShortNameAndFlavorPostfix();
  }

  /** An empty string when there are no flavors, or hash followed by comma-separated flavors. */
  public String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  protected String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
  }

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "cell//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return getUnconfiguredBuildTargetView().getFullyQualifiedName();
  }

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getCellRelativeName() {
    return getUnconfiguredBuildTargetView().getCellRelativeName();
  }

  public boolean isFlavored() {
    return getUnconfiguredBuildTargetView().isFlavored();
  }

  public abstract BuildTarget withShortName(String shortName);

  /**
   * Verifies that this build target has no flavors.
   *
   * @return this build target
   * @throws IllegalStateException if a build target has flavors
   */
  public BuildTarget assertUnflavored() {
    getUnconfiguredBuildTargetView().assertUnflavored();
    return this;
  }

  public abstract BuildTarget withoutFlavors(Set<Flavor> flavors);

  public abstract BuildTarget withoutFlavors(Flavor... flavors);

  public abstract BuildTarget withoutFlavors();

  public abstract BuildTarget withFlavors(Flavor... flavors);

  public abstract BuildTarget withFlavors(Iterable<? extends Flavor> flavors);

  public abstract BuildTarget withAppendedFlavors(Set<Flavor> flavors);

  public abstract BuildTarget withAppendedFlavors(Flavor... flavors);

  public abstract BuildTarget withUnflavoredBuildTarget(UnflavoredBuildTarget target);

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
