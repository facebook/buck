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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * This may have a TargetNode or some diagnostic information, in case the target was found
 * incompatible.
 */
public class TargetNodeMaybeIncompatible {
  private final Optional<TargetNode<?>> targetNode;
  private final BuildTarget buildTarget;
  private final ImmutableList<UnconfiguredBuildTarget> compatibleWith;

  private TargetNodeMaybeIncompatible(
      BuildTarget buildTarget,
      Optional<TargetNode<?>> targetNode,
      ImmutableList<UnconfiguredBuildTarget> compatibleWith) {
    this.buildTarget = buildTarget;
    this.targetNode = targetNode;
    this.compatibleWith = compatibleWith;
  }

  /** Constructor to create object for a target node which was incompatible. */
  public static TargetNodeMaybeIncompatible ofIncompatible(
      BuildTarget target, ImmutableList<UnconfiguredBuildTarget> compatibleWith) {
    return new TargetNodeMaybeIncompatible(target, Optional.empty(), compatibleWith);
  }

  /** Constructor to create object for a compatible target node. */
  public static TargetNodeMaybeIncompatible ofCompatible(TargetNode<?> targetNode) {
    ImmutableList<UnconfiguredBuildTarget> compatibleWith = ImmutableList.of();
    if (targetNode.getConstructorArg() instanceof BuildRuleArg) {
      compatibleWith = ((BuildRuleArg) targetNode.getConstructorArg()).getCompatibleWith();
    }
    return new TargetNodeMaybeIncompatible(
        targetNode.getBuildTarget(), Optional.of(targetNode), compatibleWith);
  }

  public TargetNode<?> assertGetTargetNode(DependencyStack dependencyStack) {
    if (targetNode.isPresent()) {
      return targetNode.get();
    }
    StringBuilder compatibles = new StringBuilder();
    compatibleWith.forEach(
        target ->
            compatibles.append(target.getFullyQualifiedName()).append(System.lineSeparator()));
    throw new HumanReadableException(
        dependencyStack,
        "Build target %s is restricted to contraints in \"compatible_with\""
            + " that do not match the target platform "
            + compatibles,
        buildTarget);
  }

  public Optional<TargetNode<?>> getTargetNodeOptional() {
    return targetNode;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public ImmutableList<UnconfiguredBuildTarget> getCompatibleWith() {
    return compatibleWith;
  }
}
