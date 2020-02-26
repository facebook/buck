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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/** Immutable implementation of {@link UnconfiguredTargetNode}. */
@BuckStylePrehashedValue
@JsonDeserialize
public abstract class ImmutableUnconfiguredTargetNode implements UnconfiguredTargetNode {
  @Override
  @JsonProperty("buildTarget")
  public abstract UnconfiguredBuildTarget getBuildTarget();

  @Override
  @JsonProperty("ruleType")
  public abstract RuleType getRuleType();

  @Override
  @JsonProperty("attributes")
  public abstract ImmutableMap<String, Object> getAttributes();

  // Visibility patterns might not really serialize/deserialize well
  // TODO: should we move them out of UnconfiguredTargetNode to TargetNode ?

  @Override
  @JsonProperty("visibilityPatterns")
  public abstract ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  @Override
  @JsonProperty("withinViewPatterns")
  public abstract ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  @Override
  @JsonProperty("defaultTargetPlatform")
  public abstract Optional<UnconfiguredBuildTarget> getDefaultTargetPlatform();

  @Override
  @JsonProperty("compatibleWith")
  public abstract ImmutableList<UnconfiguredBuildTarget> getCompatibleWith();

  public static UnconfiguredTargetNode of(
      UnconfiguredBuildTarget buildTarget,
      RuleType ruleType,
      ImmutableMap<String, Object> attributes,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
      Optional<UnconfiguredBuildTarget> defaultTargetPlatform,
      ImmutableList<UnconfiguredBuildTarget> compatibleWith) {
    return ImmutableImmutableUnconfiguredTargetNode.of(
        buildTarget,
        ruleType,
        attributes,
        visibilityPatterns,
        withinViewPatterns,
        defaultTargetPlatform,
        compatibleWith);
  }
}
