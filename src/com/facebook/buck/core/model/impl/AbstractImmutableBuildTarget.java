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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@BuckStylePackageVisibleTuple
@Value.Immutable(prehash = true, builder = false)
abstract class AbstractImmutableBuildTarget extends AbstractBuildTarget {

  @Override
  public abstract UnconfiguredBuildTarget getUnconfiguredBuildTarget();

  @Override
  public abstract TargetConfiguration getTargetConfiguration();

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

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public BuildTarget withShortName(String shortName) {
    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTarget().withShortName(shortName), getTargetConfiguration());
  }

  @Override
  public BuildTarget withoutFlavors(Set<Flavor> flavors) {
    return withFlavors(Sets.difference(getFlavors(), flavors));
  }

  @Override
  public BuildTarget withoutFlavors(Flavor... flavors) {
    return withoutFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withoutFlavors() {
    return ImmutableBuildTarget.of(
        ImmutableUnconfiguredBuildTarget.of(getUnflavoredBuildTarget()), getTargetConfiguration());
  }

  @Override
  public BuildTarget withFlavors(Flavor... flavors) {
    return withFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withFlavors(Iterable<? extends Flavor> flavors) {
    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTarget().withFlavors(flavors), getTargetConfiguration());
  }

  @Override
  public BuildTarget withAppendedFlavors(Set<Flavor> flavors) {
    return withFlavors(Sets.union(getFlavors(), flavors));
  }

  @Override
  public BuildTarget withAppendedFlavors(Flavor... flavors) {
    return withAppendedFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withUnflavoredBuildTarget(UnflavoredBuildTarget target) {
    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTarget().withUnflavoredBuildTarget(target), getTargetConfiguration());
  }

  @Override
  public BuildTarget withoutCell() {
    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTarget().withoutCell(), getTargetConfiguration());
  }
}
