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
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Arrays;
import java.util.Objects;

/** An immutable implementation of {@link UnconfiguredBuildTargetView}. */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public class ImmutableUnconfiguredBuildTargetView implements UnconfiguredBuildTargetView {

  private final UnconfiguredBuildTarget data;
  private final int hash;

  private ImmutableUnconfiguredBuildTargetView(UnconfiguredBuildTarget data) {
    this.data = data;
    this.hash = Objects.hash(this.data);
  }

  /**
   * Create new immutable instance of {@link UnconfiguredBuildTargetView}
   *
   * @param data Data object that backs this view
   */
  public static ImmutableUnconfiguredBuildTargetView of(UnconfiguredBuildTarget data) {
    return new ImmutableUnconfiguredBuildTargetView(data);
  }

  /**
   * Create new immutable instance of {@link UnconfiguredBuildTargetView}
   *
   * @param unflavoredBuildTargetView Build target without flavors
   * @param flavors Flavors that apply to this build target
   */
  public static ImmutableUnconfiguredBuildTargetView of(
      UnflavoredBuildTarget unflavoredBuildTargetView, ImmutableSortedSet<Flavor> flavors) {
    return new ImmutableUnconfiguredBuildTargetView(
        UnconfiguredBuildTarget.of(unflavoredBuildTargetView, flavors));
  }

  /** Helper for creating a build target in the root cell with no flavors. */
  public static ImmutableUnconfiguredBuildTargetView of(BaseName baseName, String shortName) {
    // TODO(buck_team): this is unsafe. It allows us to potentially create an inconsistent build
    // target where the cell name doesn't match the cell path.
    return ImmutableUnconfiguredBuildTargetView.of(
        UnflavoredBuildTarget.of(CanonicalCellName.unsafeRootCell(), baseName, shortName),
        UnconfiguredBuildTarget.NO_FLAVORS);
  }

  @Override
  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return data.getUnflavoredBuildTarget();
  }

  @JsonIgnore
  @Override
  public ImmutableSortedSet<Flavor> getFlavors() {
    return data.getFlavors();
  }

  @JsonProperty("cell")
  @Override
  public CanonicalCellName getCell() {
    return data.getCell();
  }

  @JsonProperty("baseName")
  @JsonView(JsonViews.MachineReadableLog.class)
  private String getBaseNameString() {
    return data.getBaseName().toString();
  }

  @Override
  @JsonIgnore
  public BaseName getBaseName() {
    return data.getBaseName();
  }

  @JsonIgnore
  @Override
  public CellRelativePath getCellRelativeBasePath() {
    return data.getCellRelativeBasePath();
  }

  @JsonProperty("shortName")
  @JsonView(JsonViews.MachineReadableLog.class)
  @Override
  public String getShortName() {
    return data.getName();
  }

  @JsonIgnore
  @Override
  public String getShortNameAndFlavorPostfix() {
    return getShortName() + getFlavorPostfix();
  }

  @JsonProperty("flavor")
  @JsonView(JsonViews.MachineReadableLog.class)
  private String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
  }

  private String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  @JsonIgnore
  @Override
  public String getFullyQualifiedName() {
    return data.getFullyQualifiedName();
  }

  @JsonIgnore
  @Override
  public String getCellRelativeName() {
    return data.getCellRelativeName();
  }

  @JsonIgnore
  @Override
  public boolean isFlavored() {
    return !getFlavors().isEmpty();
  }

  @Override
  public UnconfiguredBuildTargetView assertUnflavored() {
    Preconditions.checkState(!isFlavored(), "%s is flavored.", this);
    return this;
  }

  @Override
  public UnconfiguredBuildTargetView withShortName(String shortName) {
    return ImmutableUnconfiguredBuildTargetView.of(
        UnflavoredBuildTarget.of(
            getUnflavoredBuildTarget().getCell(),
            getUnflavoredBuildTarget().getBaseName(),
            shortName),
        getFlavors());
  }

  @Override
  public UnconfiguredBuildTargetView withFlavors(Flavor... flavors) {
    return withFlavors(Arrays.asList(flavors));
  }

  @Override
  @SuppressWarnings("unchecked")
  public UnconfiguredBuildTargetView withFlavors(Iterable<? extends Flavor> flavors) {
    ImmutableSortedSet<Flavor> flavorsSet;
    if (flavors instanceof ImmutableSortedSet
        && ((ImmutableSortedSet<Flavor>) flavors)
            .comparator()
            .equals(UnconfiguredBuildTarget.FLAVOR_ORDERING)) {
      flavorsSet = (ImmutableSortedSet<Flavor>) flavors;
    } else {
      flavorsSet = ImmutableSortedSet.copyOf(UnconfiguredBuildTarget.FLAVOR_ORDERING, flavors);
    }

    return ImmutableUnconfiguredBuildTargetView.of(
        UnconfiguredBuildTarget.of(getUnflavoredBuildTarget(), flavorsSet));
  }

  @Override
  public UnconfiguredBuildTargetView withoutFlavors() {
    return ImmutableUnconfiguredBuildTargetView.of(
        UnconfiguredBuildTarget.of(getUnflavoredBuildTarget(), UnconfiguredBuildTarget.NO_FLAVORS));
  }

  @Override
  public UnconfiguredBuildTargetView withUnflavoredBuildTarget(UnflavoredBuildTarget target) {
    return ImmutableUnconfiguredBuildTargetView.of(
        UnconfiguredBuildTarget.of(target, getFlavors()));
  }

  @Override
  public BuildTarget configure(TargetConfiguration targetConfiguration) {
    return ImmutableBuildTarget.of(this, targetConfiguration);
  }

  @Override
  public UnconfiguredBuildTarget getData() {
    return data;
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public boolean equals(Object another) {
    if (this == another) {
      return true;
    }
    return another instanceof ImmutableUnconfiguredBuildTargetView
        && equalTo((ImmutableUnconfiguredBuildTargetView) another);
  }

  private boolean equalTo(ImmutableUnconfiguredBuildTargetView another) {
    if (hash != another.hash) {
      return false;
    }

    return data.equals(another.data);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public int compareTo(UnconfiguredBuildTargetView o) {
    if (this == o) {
      return 0;
    }

    if (!(o instanceof ImmutableUnconfiguredBuildTargetView)) {
      return ImmutableUnconfiguredBuildTargetView.class.getName().compareTo(o.getClass().getName());
    }

    ImmutableUnconfiguredBuildTargetView other = (ImmutableUnconfiguredBuildTargetView) o;

    return this.data.compareTo(other.data);
  }
}
