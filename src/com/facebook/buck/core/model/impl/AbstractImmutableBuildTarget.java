/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.core.model.AbstractBuildTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
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

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@BuckStyleTuple
@Value.Immutable(prehash = true, builder = false)
abstract class AbstractImmutableBuildTarget extends AbstractBuildTarget {

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

  public static BuildTarget of(UnflavoredBuildTarget unflavoredBuildTarget) {
    return ImmutableBuildTarget.of(unflavoredBuildTarget, ImmutableSortedSet.of());
  }

  /** Helper for creating a build target with no flavors and no cell name. */
  public static BuildTarget of(Path cellPath, String baseName, String shortName) {
    return ImmutableBuildTarget.of(
        ImmutableUnflavoredBuildTarget.of(cellPath, Optional.empty(), baseName, shortName));
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public BuildTarget withoutFlavors(Set<Flavor> flavors) {
    return ImmutableBuildTarget.of(
        getUnflavoredBuildTarget(), Sets.difference(getFlavors(), flavors));
  }

  @Override
  public BuildTarget withoutFlavors(Flavor... flavors) {
    return withoutFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withoutFlavors() {
    return ImmutableBuildTarget.of(getUnflavoredBuildTarget());
  }

  @Override
  public BuildTarget withAppendedFlavors(Set<Flavor> flavors) {
    return ImmutableBuildTarget.of(getUnflavoredBuildTarget(), Sets.union(getFlavors(), flavors));
  }

  @Override
  public BuildTarget withAppendedFlavors(Flavor... flavors) {
    return withAppendedFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withoutCell() {
    return ImmutableBuildTarget.of(
        ImmutableUnflavoredBuildTarget.of(
            getCellPath(), Optional.empty(), getBaseName(), getShortName()),
        getFlavors());
  }
}
