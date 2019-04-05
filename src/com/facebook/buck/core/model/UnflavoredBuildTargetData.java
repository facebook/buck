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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import org.immutables.value.Value;

/**
 * Data object that holds properties to uniquely identify a build target when no flavors are used
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
@JsonDeserialize
public abstract class UnflavoredBuildTargetData implements Comparable<UnflavoredBuildTargetData> {

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

  /** Performs validation of input data */
  @Value.Check
  protected void check() {
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
  public int compareTo(UnflavoredBuildTargetData o) {
    return ComparisonChain.start()
        .compare(getCell(), o.getCell())
        .compare(getBaseName(), o.getBaseName())
        .compare(getName(), o.getName())
        .result();
  }
}
