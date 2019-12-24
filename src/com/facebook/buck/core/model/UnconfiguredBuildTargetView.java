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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Represents a view over {@link UnconfiguredBuildTarget}.
 *
 * <p>This view adds runtime context information to {@link UnconfiguredBuildTarget}, like absolute
 * cell path where build target belongs to. Because of that, this class should not be used as a
 * long-living data class with the lifetime exceeding the lifetime of running command.
 *
 * <p>This class represents a legacy data structure. In most of the cases you will want to use
 * {@link UnconfiguredBuildTarget} directly.
 */
@ThreadSafe
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public class UnconfiguredBuildTargetView
    implements Comparable<UnconfiguredBuildTargetView>, DependencyStack.Element {

  private final UnconfiguredBuildTarget data;
  private final int hash;

  private UnconfiguredBuildTargetView(UnconfiguredBuildTarget data) {
    this.data = data;
    this.hash = Objects.hash(this.data);
  }

  /**
   * Create new immutable instance of {@link UnconfiguredBuildTargetView}
   *
   * @param data Data object that backs this view
   */
  public static UnconfiguredBuildTargetView of(UnconfiguredBuildTarget data) {
    return new UnconfiguredBuildTargetView(data);
  }

  /**
   * Create new immutable instance of {@link UnconfiguredBuildTargetView}
   *
   * @param unflavoredBuildTargetView Build target without flavors
   * @param flavors Flavors that apply to this build target
   */
  public static UnconfiguredBuildTargetView of(
      UnflavoredBuildTarget unflavoredBuildTargetView, ImmutableSortedSet<Flavor> flavors) {
    return new UnconfiguredBuildTargetView(
        UnconfiguredBuildTarget.of(unflavoredBuildTargetView, flavors));
  }

  /** Helper for creating a build target in the root cell with no flavors. */
  public static UnconfiguredBuildTargetView of(BaseName baseName, String shortName) {
    // TODO(buck_team): this is unsafe. It allows us to potentially create an inconsistent build
    // target where the cell name doesn't match the cell path.
    return UnconfiguredBuildTargetView.of(
        UnflavoredBuildTarget.of(CanonicalCellName.unsafeRootCell(), baseName, shortName),
        UnconfiguredBuildTarget.NO_FLAVORS);
  }

  /** A build target without flavors. */
  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return data.getUnflavoredBuildTarget();
  }

  /** Set of flavors used with that build target. */
  @JsonIgnore
  public ImmutableSortedSet<Flavor> getFlavors() {
    return data.getFlavors();
  }

  /**
   * The canonical name of the cell specified in the build target.
   *
   * <p>Note that this name can be different from the name specified on the name. See {@link
   * com.facebook.buck.core.cell.CellPathResolver#getCanonicalCellName} for more information.
   */
  @JsonProperty("cell")
  public CanonicalCellName getCell() {
    return data.getCell();
  }

  @JsonProperty("baseName")
  @JsonView(JsonViews.MachineReadableLog.class)
  private String getBaseNameString() {
    return data.getBaseName().toString();
  }

  /**
   * Part of build target name the colon excluding cell name.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava} this returns {@code
   * //third_party/java/guava}.
   */
  @JsonIgnore
  public BaseName getBaseName() {
    return data.getBaseName();
  }

  /**
   * The path of the directory where this target is located.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava} this returns {@code
   * cell//third_party/java/guava}.
   */
  @JsonIgnore
  public CellRelativePath getCellRelativeBasePath() {
    return data.getCellRelativeBasePath();
  }

  /**
   * The part of the build target name after the colon.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava-latest} this returns {@code
   * guava-latest}.
   */
  @JsonProperty("shortName")
  @JsonView(JsonViews.MachineReadableLog.class)
  public String getShortName() {
    return data.getName();
  }

  /**
   * The short name with flavors.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava-latest#flavor} this returns
   * {@code guava-latest#flavor}.
   */
  @JsonIgnore
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

  /**
   * The full name of the build target including the cell name and flavors.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava-latest#flavor} this returns
   * {@code cell//third_party/java/guava:guava-latest#flavor}.
   */
  @JsonIgnore
  public String getFullyQualifiedName() {
    return data.getFullyQualifiedName();
  }

  /**
   * The name of the build target including flavors but excluding the cell.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava-latest#flavor} this returns
   * {@code //third_party/java/guava:guava-latest#flavor}.
   */
  @JsonIgnore
  public String getCellRelativeName() {
    return data.getCellRelativeName();
  }

  /** Whether this target contains flavors. */
  @JsonIgnore
  public boolean isFlavored() {
    return !getFlavors().isEmpty();
  }

  /**
   * Verifies that this build target has no flavors.
   *
   * @return this build target
   * @throws IllegalStateException if a build target has flavors
   */
  public UnconfiguredBuildTargetView assertUnflavored() {
    Preconditions.checkState(!isFlavored(), "%s is flavored.", this);
    return this;
  }

  /**
   * Creates a new build target by copying all of the information from this build target and
   * replacing the short name with the given name.
   *
   * @param shortName short name of the new build target
   */
  public UnconfiguredBuildTargetView withShortName(String shortName) {
    return UnconfiguredBuildTargetView.of(
        UnflavoredBuildTarget.of(
            getUnflavoredBuildTarget().getCell(),
            getUnflavoredBuildTarget().getBaseName(),
            shortName),
        getFlavors());
  }

  /**
   * Creates a new build target by copying all of the information from this build target and using
   * the provided flavors as flavors in the new build target.
   *
   * @param flavors flavors to use when creating a new build target
   */
  public UnconfiguredBuildTargetView withFlavors(Flavor... flavors) {
    return withFlavors(Arrays.asList(flavors));
  }

  /**
   * Creates a new build target by copying all of the information from this build target and using
   * the provided flavors as flavors in the new build target.
   *
   * @param flavors flavors to use when creating a new build target
   */
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

    return UnconfiguredBuildTargetView.of(
        UnconfiguredBuildTarget.of(getUnflavoredBuildTarget(), flavorsSet));
  }

  /**
   * Creates a new build target by using the provided {@link UnflavoredBuildTarget} and flavors from
   * this build target.
   */
  public UnconfiguredBuildTargetView withoutFlavors() {
    return UnconfiguredBuildTargetView.of(
        UnconfiguredBuildTarget.of(getUnflavoredBuildTarget(), UnconfiguredBuildTarget.NO_FLAVORS));
  }

  public UnconfiguredBuildTargetView withUnflavoredBuildTarget(UnflavoredBuildTarget target) {
    return UnconfiguredBuildTargetView.of(UnconfiguredBuildTarget.of(target, getFlavors()));
  }

  /**
   * Creates {@link BuildTarget} by attaching {@link TargetConfiguration} to this unconfigured build
   * target.
   */
  public BuildTarget configure(TargetConfiguration targetConfiguration) {
    return BuildTarget.of(this, targetConfiguration);
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
    return another instanceof UnconfiguredBuildTargetView
        && equalTo((UnconfiguredBuildTargetView) another);
  }

  private boolean equalTo(UnconfiguredBuildTargetView another) {
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
  public int compareTo(UnconfiguredBuildTargetView that) {
    if (this == that) {
      return 0;
    }

    return this.data.compareTo(that.data);
  }

  /** Return a data object that backs current view */
  public UnconfiguredBuildTarget getData() {
    return data;
  }
}
