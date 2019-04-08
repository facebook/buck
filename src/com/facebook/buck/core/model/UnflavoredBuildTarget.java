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
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import org.immutables.value.Value;

/**
 * Data object that holds properties to uniquely identify a build target when no flavors are used
 *
 * <p>In other words, this represents a parsed representation of a build target without flavors or
 * configuration.
 *
 * <p>For example, a fully qualified target name like `cell//path/to:target` parses `cell` as a cell
 * name, `//path/to` as a base name that corresponds to the real path to the build file that
 * contains a target and `target` is a target name found in that build file
 *
 * <p>This class is rarely used along, but usually as a part of {@link UnconfiguredBuildTarget}
 * which adds flavors to it. In the future both of them will be probably merged into one.
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
@JsonDeserialize
public abstract class UnflavoredBuildTarget implements Comparable<UnflavoredBuildTarget> {

  private static final String BUILD_TARGET_PREFIX = "//";

  /** Name of the cell that current build target belongs to */
  @Value.Parameter
  @JsonProperty("cell")
  public abstract String getCell();

  /**
   * Base name of build target, i.e. part of fully qualified name before the colon If this build
   * target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava"
   */
  @Value.Parameter
  @JsonProperty("baseName")
  public abstract String getBaseName();

  /**
   * Name of the buid target, i.e. part of fully qualified name after the colon If this build target
   * were //third_party/java/guava:guava-latest, then this would return "guava-latest"
   */
  @Value.Parameter
  @JsonProperty("name")
  public abstract String getName();

  /** Fully qualified name of unconfigured build target, for example cell//some/target:name */
  @Value.Lazy
  @JsonIgnore
  public String getFullyQualifiedName() {
    return getCell() + getBaseName() + ":" + getName();
  }

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  /** Performs validation of input data */
  @Value.Check
  protected void check() {

    // this check is not always required but may be expensive
    // TODO(buck_team): only validate data if provided as a user input

    Preconditions.checkArgument(
        getBaseName().startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with %s but was %s",
        BUILD_TARGET_PREFIX,
        getBaseName());

    // BaseName may contain backslashes, which are the path separator, so not permitted.
    Preconditions.checkArgument(
        !getBaseName().contains("\\"), "baseName may not contain backslashes.");

    Preconditions.checkArgument(
        !getName().contains("#"), "Build target name cannot contain '#' but was: %s.", getName());
  }

  @Override
  public int compareTo(UnflavoredBuildTarget o) {
    return ComparisonChain.start()
        .compare(getCell(), o.getCell())
        .compare(getBaseName(), o.getBaseName())
        .compare(getName(), o.getName())
        .result();
  }
}
