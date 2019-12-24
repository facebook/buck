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
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Joiner;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Objects;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public class BuildTarget implements Comparable<BuildTarget>, DependencyStack.Element {

  private final UnconfiguredBuildTargetView unconfiguredBuildTargetView;
  private final TargetConfiguration targetConfiguration;
  private final int hash;

  private BuildTarget(
      UnconfiguredBuildTargetView unconfiguredBuildTargetView,
      TargetConfiguration targetConfiguration) {
    this.unconfiguredBuildTargetView = unconfiguredBuildTargetView;
    this.targetConfiguration = targetConfiguration;
    this.hash = Objects.hash(unconfiguredBuildTargetView, targetConfiguration);
  }

  static BuildTarget of(
      UnconfiguredBuildTargetView unconfiguredBuildTargetView,
      TargetConfiguration targetConfiguration) {
    return new BuildTarget(unconfiguredBuildTargetView, targetConfiguration);
  }

  public UnconfiguredBuildTargetView getUnconfiguredBuildTargetView() {
    return unconfiguredBuildTargetView;
  }

  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return unconfiguredBuildTargetView.getUnflavoredBuildTarget();
  }

  public ImmutableSortedSet<Flavor> getFlavors() {
    return unconfiguredBuildTargetView.getFlavors();
  }

  public TargetConfiguration getTargetConfiguration() {
    return targetConfiguration;
  }

  @JsonProperty("cell")
  public CanonicalCellName getCell() {
    return unconfiguredBuildTargetView.getCell();
  }

  public BaseName getBaseName() {
    return unconfiguredBuildTargetView.getBaseName();
  }

  public CellRelativePath getCellRelativeBasePath() {
    return unconfiguredBuildTargetView.getCellRelativeBasePath();
  }

  @JsonProperty("baseName")
  @JsonView(JsonViews.MachineReadableLog.class)
  private String getBaseNameString() {
    return getBaseName().toString();
  }

  @JsonProperty("shortName")
  @JsonView(JsonViews.MachineReadableLog.class)
  public String getShortName() {
    return unconfiguredBuildTargetView.getShortName();
  }

  /**
   * If this build target were cell//third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  public String getShortNameAndFlavorPostfix() {
    return unconfiguredBuildTargetView.getShortNameAndFlavorPostfix();
  }

  /** An empty string when there are no flavors, or hash followed by comma-separated flavors. */
  public String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  @JsonProperty("flavor")
  @JsonView(JsonViews.MachineReadableLog.class)
  protected String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
  }

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "cell//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return unconfiguredBuildTargetView.getFullyQualifiedName();
  }

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getCellRelativeName() {
    return unconfiguredBuildTargetView.getCellRelativeName();
  }

  public boolean isFlavored() {
    return unconfiguredBuildTargetView.isFlavored();
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  public BuildTarget withShortName(String shortName) {
    return BuildTarget.of(
        unconfiguredBuildTargetView.withShortName(shortName), targetConfiguration);
  }

  /**
   * Verifies that this build target has no flavors.
   *
   * @return this build target
   * @throws IllegalStateException if a build target has flavors
   */
  public BuildTarget assertUnflavored() {
    unconfiguredBuildTargetView.assertUnflavored();
    return this;
  }

  public BuildTarget withoutFlavors(Set<Flavor> flavors) {
    return withFlavors(Sets.difference(getFlavors(), flavors));
  }

  public BuildTarget withoutFlavors(Flavor... flavors) {
    return withoutFlavors(ImmutableSet.copyOf(flavors));
  }

  /** A copy of this build target but without any flavors. */
  public BuildTarget withoutFlavors() {
    if (getFlavors().isEmpty()) {
      return this;
    }

    return BuildTarget.of(unconfiguredBuildTargetView.withoutFlavors(), targetConfiguration);
  }

  public BuildTarget withFlavors(Flavor... flavors) {
    return withFlavors(ImmutableSet.copyOf(flavors));
  }

  public BuildTarget withFlavors(Iterable<? extends Flavor> flavors) {
    return BuildTarget.of(unconfiguredBuildTargetView.withFlavors(flavors), targetConfiguration);
  }

  public BuildTarget withAppendedFlavors(Set<Flavor> flavors) {
    return withFlavors(Sets.union(getFlavors(), flavors));
  }

  public BuildTarget withAppendedFlavors(Flavor... flavors) {
    return withAppendedFlavors(ImmutableSet.copyOf(flavors));
  }

  /** Keep flavors and configuration, replace everything else. */
  public BuildTarget withUnflavoredBuildTarget(UnflavoredBuildTarget target) {
    return BuildTarget.of(
        unconfiguredBuildTargetView.withUnflavoredBuildTarget(target), targetConfiguration);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BuildTarget that = (BuildTarget) o;
    return hash == that.hash
        && unconfiguredBuildTargetView.equals(that.unconfiguredBuildTargetView)
        && targetConfiguration.equals(that.targetConfiguration);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public int compareTo(BuildTarget that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(unconfiguredBuildTargetView, that.unconfiguredBuildTargetView)
        .compare(targetConfiguration, that.targetConfiguration)
        .result();
  }
}
