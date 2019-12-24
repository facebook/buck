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
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Set;
import org.immutables.value.Value;

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@BuckStylePackageVisibleTuple
@Value.Immutable(prehash = true, builder = false)
abstract class AbstractImmutableBuildTarget extends BuildTarget {

  @Override
  public abstract UnconfiguredBuildTargetView getUnconfiguredBuildTargetView();

  @Override
  public abstract TargetConfiguration getTargetConfiguration();

  @JsonProperty("cell")
  @Override
  public CanonicalCellName getCell() {
    return super.getCell();
  }

  @Override
  @JsonIgnore
  public BaseName getBaseName() {
    return super.getBaseName();
  }

  @JsonProperty("baseName")
  @JsonView(JsonViews.MachineReadableLog.class)
  private String getBaseNameString() {
    return getBaseName().toString();
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
        getUnconfiguredBuildTargetView().withShortName(shortName), getTargetConfiguration());
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
    if (getFlavors().isEmpty()) {
      return this;
    }

    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTargetView().withoutFlavors(), getTargetConfiguration());
  }

  @Override
  public BuildTarget withFlavors(Flavor... flavors) {
    return withFlavors(ImmutableSet.copyOf(flavors));
  }

  @Override
  public BuildTarget withFlavors(Iterable<? extends Flavor> flavors) {
    return ImmutableBuildTarget.of(
        getUnconfiguredBuildTargetView().withFlavors(flavors), getTargetConfiguration());
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
        getUnconfiguredBuildTargetView().withUnflavoredBuildTarget(target),
        getTargetConfiguration());
  }

  @Override
  @JsonIgnore
  public DependencyStack.Element getElement() {
    return this;
  }
}
