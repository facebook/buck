/*
 * Copyright 2014-present Facebook, Inc.
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

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
@Value.Immutable(builder = false, copy = false, prehash = true)
@JsonDeserialize
public abstract class UnconfiguredBuildTarget implements Comparable<UnconfiguredBuildTarget> {

  private static final Ordering<Iterable<Flavor>> LEXICOGRAPHICAL_ORDERING =
      Ordering.<Flavor>natural().lexicographical();

  /** Flavors passed to this object should be sorted using this ordering */
  public static final Ordering<Flavor> FLAVOR_ORDERING = Ordering.natural();

  /** Build target without flavors */
  @Value.Parameter
  @JsonProperty("unflavoredBuildTarget")
  public abstract UnflavoredBuildTarget getUnflavoredBuildTarget();

  /** Set of flavors used with that build target. */
  @Value.Parameter
  @JsonProperty("flavors")
  public abstract ImmutableSortedSet<Flavor> getFlavors();

  /** Validation for flavor ordering */
  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getFlavors().comparator() == FLAVOR_ORDERING,
        "Flavors must be ordered using natural ordering.");
  }

  /**
   * Fully qualified name of unconfigured build target, for example
   * cell//some/target:name#flavor1,flavor2 *
   */
  @Value.Lazy
  @JsonIgnore
  public String getFullyQualifiedName() {
    return getUnflavoredBuildTarget() + getFlavorPostfix();
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
  public int compareTo(UnconfiguredBuildTarget o) {
    if (this == o) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(getUnflavoredBuildTarget(), o.getUnflavoredBuildTarget())
        .compare(getFlavors(), o.getFlavors(), LEXICOGRAPHICAL_ORDERING)
        .result();
  }
}
