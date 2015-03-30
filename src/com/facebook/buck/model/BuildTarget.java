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

package com.facebook.buck.model;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.SortedSet;

import javax.annotation.Nullable;

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@BuckStyleImmutable
@Value.Immutable
public abstract class BuildTarget
    implements
        Comparable<BuildTarget>,
        HasUnflavoredBuildTarget,
        HasBuildTarget {

  @Override
  @Value.Parameter
  public abstract UnflavoredBuildTarget getUnflavoredBuildTarget();

  @Value.NaturalOrder
  @Value.Parameter
  public abstract SortedSet<Flavor> getFlavors();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getFlavors().comparator() == Ordering.natural(),
        "Flavors must be ordered using natural ordering.");
  }

  @JsonProperty("repository")
  public Optional<String> getRepository() {
    return getUnflavoredBuildTarget().getRepository();
  }

  @JsonProperty("baseName")
  public String getBaseName() {
    return getUnflavoredBuildTarget().getBaseName();
  }

  @Nullable
  public String getBaseNameWithSlash() {
    return getUnflavoredBuildTarget().getBaseNameWithSlash();
  }

  public Path getBasePath() {
    return getUnflavoredBuildTarget().getBasePath();
  }

  public String getBasePathWithSlash() {
    return getUnflavoredBuildTarget().getBasePathWithSlash();
  }

  @JsonProperty("shortName")
  public String getShortName() {
    return getUnflavoredBuildTarget().getShortName();
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  public String getShortNameAndFlavorPostfix() {
    return getShortName() + getFlavorPostfix();
  }

  public String getFlavorPostfix() {
    if (getFlavors().isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  @JsonProperty("flavor")
  private String getFlavorsAsString() {
    return Joiner.on(",").join(getFlavors());
  }

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  @Value.Derived
  public String getFullyQualifiedName() {
    return getUnflavoredBuildTarget().getFullyQualifiedName() + getFlavorPostfix();
  }

  @JsonIgnore
  public boolean isFlavored() {
    return !(getFlavors().isEmpty());
  }

  public UnflavoredBuildTarget checkUnflavored() {
    Preconditions.checkState(!isFlavored(), "%s is flavored.", this);
    return getUnflavoredBuildTarget();
  }

  public static BuildTarget of(UnflavoredBuildTarget unflavoredBuildTarget) {
    return ImmutableBuildTarget.of(
        unflavoredBuildTarget,
        ImmutableSortedSet.<Flavor>of());
  }

  public static ImmutableBuildTarget.Builder builder(BuildTarget buildTarget) {
    return ImmutableBuildTarget
        .builder()
        .setUnflavoredBuildTarget(buildTarget.getUnflavoredBuildTarget())
        .addAllFlavors(buildTarget.getFlavors());
  }

  public static ImmutableBuildTarget.Builder builder(UnflavoredBuildTarget buildTarget) {
    return ImmutableBuildTarget
        .builder()
        .setUnflavoredBuildTarget(buildTarget);
  }

  public static ImmutableBuildTarget.Builder builder(String baseName, String shortName) {
    return ImmutableBuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            ImmutableUnflavoredBuildTarget.of(Optional.<String>absent(), baseName, shortName));
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public int compareTo(@Nullable BuildTarget target) {
    Preconditions.checkNotNull(target);
    return getFullyQualifiedName().compareTo(target.getFullyQualifiedName());
  }

  @Override
  public BuildTarget getBuildTarget() {
    return this;
  }

}
