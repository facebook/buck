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

/** Data object that holds properties to uniquely identify a build target with flavors */
@Value.Immutable(builder = false, copy = false, prehash = true)
@JsonDeserialize
public abstract class UnconfiguredBuildTargetData
    implements Comparable<UnconfiguredBuildTargetData> {

  private static final Ordering<Iterable<Flavor>> LEXICOGRAPHICAL_ORDERING =
      Ordering.<Flavor>natural().lexicographical();

  /** Flavors passed to this object should be sorted using this ordering */
  public static final Ordering<Flavor> FLAVOR_ORDERING = Ordering.natural();

  /** Build target without flavors */
  @Value.Parameter
  @JsonProperty("unflavoredBuildTarget")
  public abstract UnflavoredBuildTargetData getUnflavoredBuildTarget();

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
  public int compareTo(UnconfiguredBuildTargetData o) {
    if (this == o) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(getUnflavoredBuildTarget(), o.getUnflavoredBuildTarget())
        .compare(getFlavors(), o.getFlavors(), LEXICOGRAPHICAL_ORDERING)
        .result();
  }
}
