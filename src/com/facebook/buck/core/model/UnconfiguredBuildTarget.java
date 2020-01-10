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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.collect.Ordering;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Data object that holds properties to uniquely identify a build target with flavors
 *
 * <p>In other words, this represents a parsed representation of a build target with flavors but
 * without configuration.
 *
 * <p>For example, a fully qualified target name like `cell//path/to:target#flavor1,flavor2` parses
 * `cell` as a cell name, `//path/to` as a base name that corresponds to the real path to the build
 * file that contains a target, `target` is a target name found in that build file and `flavor1` and
 * 'flavor2' as flavors as applied to this build target.
 *
 * <p>Flavors are a legacy way to configure a build target so it can mutate its behavior based on
 * user-provided input or client settings, like target or running platforms. Flavors should not be
 * used anymore, instead you want to use a {@link BuildTarget} along with passed {@link
 * TargetConfiguration}.
 */
@JsonDeserialize
public class UnconfiguredBuildTarget
    implements Comparable<UnconfiguredBuildTarget>, QueryTarget, DependencyStack.Element {

  private static final Ordering<Iterable<Flavor>> LEXICOGRAPHICAL_ORDERING =
      Ordering.<Flavor>natural().lexicographical();

  /** Flavors passed to this object should be sorted using this ordering */
  public static final Ordering<Flavor> FLAVOR_ORDERING = Ordering.natural();

  /** Indicates empty set of flavors */
  public static final ImmutableSortedSet<Flavor> NO_FLAVORS =
      ImmutableSortedSet.orderedBy(FLAVOR_ORDERING).build();

  private final UnflavoredBuildTarget unflavoredBuildTarget;
  private final ImmutableSortedSet<Flavor> flavors;
  private final int hash;

  private UnconfiguredBuildTarget(
      UnflavoredBuildTarget unflavoredBuildTarget, ImmutableSortedSet<Flavor> flavors) {
    Preconditions.checkArgument(flavors.comparator() == FLAVOR_ORDERING);
    this.unflavoredBuildTarget = unflavoredBuildTarget;
    this.flavors = flavors;
    this.hash = Objects.hash(unflavoredBuildTarget, flavors);
  }

  @JsonIgnore
  public UnflavoredBuildTarget getUnflavoredBuildTarget() {
    return unflavoredBuildTarget;
  }

  /** Name of the cell that current build target belongs to */
  @JsonProperty("cell")
  public CanonicalCellName getCell() {
    return getCellRelativeBasePath().getCellName();
  }

  /**
   * Base name of build target, i.e. part of fully qualified name before the colon If this build
   * target were cell_name//third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava"
   */
  @JsonProperty("baseName")
  private String getBaseNameString() {
    return getBaseName().toString();
  }

  @JsonIgnore
  public BaseName getBaseName() {
    return BaseName.ofPath(getCellRelativeBasePath().getPath());
  }

  /** Typed version of {@link #getBaseName()}. */
  @JsonIgnore
  public CellRelativePath getCellRelativeBasePath() {
    return getUnflavoredBuildTarget().getCellRelativeBasePath();
  }

  /**
   * Name of the build target, i.e. part of fully qualified name after the colon If this build
   * target were cell_name//third_party/java/guava:guava-latest, then this would return
   * "guava-latest"
   */
  @JsonProperty("name")
  public String getName() {
    return getUnflavoredBuildTarget().getLocalName();
  }

  /** Set of flavors used with that build target. */
  @JsonProperty("flavors")
  public ImmutableSortedSet<Flavor> getFlavors() {
    return flavors;
  }

  @Nullable private String fullyQualifiedName;

  /**
   * Fully qualified name of unconfigured build target, for example
   * cell//some/target:name#flavor1,flavor2
   */
  @JsonIgnore
  public String getFullyQualifiedName() {
    if (fullyQualifiedName == null) {
      fullyQualifiedName = getUnflavoredBuildTarget().getFullyQualifiedName() + getFlavorPostfix();
    }
    return fullyQualifiedName;
  }

  @JsonIgnore
  private String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  @JsonIgnore
  private String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
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
    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }
    UnconfiguredBuildTarget that = (UnconfiguredBuildTarget) o;
    return this.hash == that.hash
        && this.unflavoredBuildTarget.equals(that.unflavoredBuildTarget)
        && this.flavors.equals(that.flavors);
  }

  @Override
  public int compareTo(UnconfiguredBuildTarget that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(this.unflavoredBuildTarget, that.unflavoredBuildTarget)
        .compare(this.flavors, that.flavors, LEXICOGRAPHICAL_ORDERING)
        .result();
  }

  @JsonIgnore
  public String getCellRelativeName() {
    return getBaseName() + ":" + getName() + getFlavorPostfix();
  }

  @Override
  @JsonIgnore
  public DependencyStack.Element getElement() {
    return this;
  }

  private static final Interner<UnconfiguredBuildTarget> interner = Interners.newWeakInterner();

  /** A constructor */
  public static UnconfiguredBuildTarget of(
      UnflavoredBuildTarget unflavoredBuildTarget, ImmutableSortedSet<Flavor> flavors) {
    return interner.intern(new UnconfiguredBuildTarget(unflavoredBuildTarget, flavors));
  }

  /** A constructor */
  private static UnconfiguredBuildTarget of(
      CellRelativePath cellRelativePath, String name, ImmutableSortedSet<Flavor> flavors) {
    return of(UnflavoredBuildTarget.of(cellRelativePath, name), flavors);
  }

  /** A constructor */
  public static UnconfiguredBuildTarget of(
      CanonicalCellName cell, BaseName baseName, String name, ImmutableSortedSet<Flavor> flavors) {
    return of(CellRelativePath.of(cell, baseName.getPath()), name, flavors);
  }

  @JsonCreator
  static UnconfiguredBuildTarget fromJson(
      @JsonProperty("cell") CanonicalCellName cell,
      @JsonProperty("baseName") String baseName,
      @JsonProperty("name") String name,
      @JsonProperty("flavors") ImmutableSortedSet<Flavor> flavors) {
    return of(cell, BaseName.of(baseName), name, flavors);
  }
}
