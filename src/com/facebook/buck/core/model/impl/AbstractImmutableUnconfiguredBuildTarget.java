/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.AbstractUnconfiguredBuildTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** An immutable implementation of {@link UnconfiguredBuildTarget}. */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@BuckStyleTuple
@Value.Immutable(prehash = true, builder = false)
abstract class AbstractImmutableUnconfiguredBuildTarget extends AbstractUnconfiguredBuildTarget {

  @Override
  public abstract UnflavoredBuildTarget getUnflavoredBuildTarget();

  @Override
  @Value.NaturalOrder
  public abstract ImmutableSortedSet<Flavor> getFlavors();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getFlavors().comparator() == Ordering.natural(),
        "Flavors must be ordered using natural ordering.");
  }

  @JsonProperty("cell")
  @Override
  public Optional<String> getCell() {
    return super.getCell();
  }

  @JsonProperty("baseName")
  @JsonView(JsonViews.MachineReadableLog.class)
  @Override
  public String getBaseName() {
    return super.getBaseName();
  }

  @JsonProperty("shortName")
  @JsonView(JsonViews.MachineReadableLog.class)
  @Override
  public String getShortName() {
    return super.getShortName();
  }

  @JsonProperty("flavor")
  @JsonView(JsonViews.MachineReadableLog.class)
  @Override
  protected String getFlavorsAsString() {
    return super.getFlavorsAsString();
  }

  @Value.Auxiliary
  @Value.Lazy
  @Override
  public String getFullyQualifiedName() {
    return super.getFullyQualifiedName();
  }

  @JsonIgnore
  @Override
  public boolean isFlavored() {
    return super.isFlavored();
  }

  @Override
  public UnconfiguredBuildTarget withShortName(String shortName) {
    return ImmutableUnconfiguredBuildTarget.of(
        ImmutableUnflavoredBuildTarget.of(
            getUnflavoredBuildTarget().getCellPath(),
            getUnflavoredBuildTarget().getCell(),
            getUnflavoredBuildTarget().getBaseName(),
            shortName),
        getFlavors());
  }

  @Override
  public UnconfiguredBuildTarget withoutFlavors(Set<Flavor> flavors) {
    return ImmutableUnconfiguredBuildTarget.of(
        getUnflavoredBuildTarget(), Sets.difference(getFlavors(), flavors));
  }

  @Override
  public UnconfiguredBuildTarget withoutFlavors(Flavor... flavors) {
    return withoutFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public UnconfiguredBuildTarget withoutFlavors() {
    return ImmutableUnconfiguredBuildTarget.of(getUnflavoredBuildTarget());
  }

  @Override
  public UnconfiguredBuildTarget withAppendedFlavors(Set<Flavor> flavors) {
    return ImmutableUnconfiguredBuildTarget.of(
        getUnflavoredBuildTarget(), Sets.union(getFlavors(), flavors));
  }

  @Override
  public UnconfiguredBuildTarget withAppendedFlavors(Flavor... flavors) {
    return withAppendedFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public UnconfiguredBuildTarget withoutCell() {
    return ImmutableUnconfiguredBuildTarget.of(
        ImmutableUnflavoredBuildTarget.of(
            getCellPath(), Optional.empty(), getBaseName(), getShortName()),
        getFlavors());
  }

  @Override
  public BuildTarget configure(TargetConfiguration targetConfiguration) {
    return ImmutableBuildTarget.of(this, targetConfiguration);
  }

  public static UnconfiguredBuildTarget of(UnflavoredBuildTarget unflavoredBuildTarget) {
    return ImmutableUnconfiguredBuildTarget.of(unflavoredBuildTarget, ImmutableSortedSet.of());
  }

  /** Helper for creating a build target with no flavors and no cell name. */
  public static UnconfiguredBuildTarget of(Path cellPath, String baseName, String shortName) {
    return ImmutableUnconfiguredBuildTarget.of(
        ImmutableUnflavoredBuildTarget.of(cellPath, Optional.empty(), baseName, shortName));
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }
}
