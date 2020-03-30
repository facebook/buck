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

package com.facebook.buck.core.model.targetgraph.raw;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.exceptions.ParsingError;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/**
 * Represents all {@link UnconfiguredTargetNodeWithDeps} that result from parsing a single build
 * file
 */
@BuckStyleValue
@JsonDeserialize
public abstract class UnconfiguredTargetNodeWithDepsPackage implements ComputeResult {

  /** Package path, relative to parse root, usually cell root */
  @JsonProperty("path")
  public abstract Path getPackagePath();

  /**
   * All {@link UnconfiguredTargetNodeWithDeps} which comes from the same build package, i.e. result
   * from parsing a single build file. Key is a string representing short build target name (last
   * part of the name after the colon) and value is corresponding target.
   */
  @JsonProperty("nodes")
  public abstract ImmutableMap<String, UnconfiguredTargetNodeWithDeps>
      getUnconfiguredTargetNodesWithDeps();

  /**
   * Errors that occurred parsing this package. If errors exist, package may be incomplete, i.e.
   * some or all target nodes may be missing
   */
  @JsonProperty("errors")
  public abstract ImmutableList<ParsingError> getErrors();

  /** Set of extension files read during parsing. */
  @JsonProperty("includes")
  public abstract ImmutableSet<Path> getIncludes();

  public static UnconfiguredTargetNodeWithDepsPackage of(
      Path packagePath,
      ImmutableMap<String, UnconfiguredTargetNodeWithDeps> unconfiguredTargetNodesWithDeps,
      ImmutableList<ParsingError> errors,
      ImmutableSet<Path> includes) {
    return ImmutableUnconfiguredTargetNodeWithDepsPackage.of(
        packagePath, unconfiguredTargetNodesWithDeps, errors, includes);
  }
}
