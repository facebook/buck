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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * This may have a TargetNode or some diagnostic information, in case the target was found
 * incompatible.
 */
public abstract class TargetNodeMaybeIncompatible {

  private TargetNodeMaybeIncompatible() {}

  private static class Compatible extends TargetNodeMaybeIncompatible {

    private final TargetNode<?> targetNode;

    private Compatible(TargetNode<?> targetNode) {
      this.targetNode = targetNode;
    }

    @Override
    public TargetNode<?> assertGetTargetNode(DependencyStack dependencyStack) {
      return targetNode;
    }

    @Override
    public Optional<TargetNode<?>> getTargetNodeOptional() {
      return Optional.of(targetNode);
    }

    @Override
    public BuildTarget getBuildTarget() {
      return targetNode.getBuildTarget();
    }
  }

  private static class Incompatible extends TargetNodeMaybeIncompatible {

    protected final ImmutableList<UnflavoredBuildTarget> compatibleWith;
    protected final BuildTarget buildTarget;
    private final Platform targetPlatform;

    private Incompatible(
        BuildTarget buildTarget,
        ImmutableList<UnflavoredBuildTarget> compatibleWith,
        Platform targetPlatform) {
      this.buildTarget = buildTarget;
      this.targetPlatform = targetPlatform;
      this.compatibleWith = compatibleWith;
    }

    @Override
    public TargetNode<?> assertGetTargetNode(DependencyStack dependencyStack) {
      StringBuilder diagnostics = new StringBuilder();
      if (!compatibleWith.isEmpty()) {
        diagnostics.append("%nTarget compatible with configurations:%n");
        compatibleWith.forEach(
            target ->
                diagnostics.append(target.getFullyQualifiedName()).append(System.lineSeparator()));
      }

      throw new HumanReadableException(
          dependencyStack,
          "Build target %s is restricted to constraints in \"compatible_with\""
              + " that do not match the target platform %s."
              + diagnostics,
          buildTarget,
          targetPlatform);
    }

    @Override
    public Optional<TargetNode<?>> getTargetNodeOptional() {
      return Optional.empty();
    }

    @Override
    public BuildTarget getBuildTarget() {
      return buildTarget;
    }
  }

  /** Constructor to create object for a target node which was incompatible. */
  public static TargetNodeMaybeIncompatible ofIncompatible(
      BuildTarget target,
      ImmutableList<UnflavoredBuildTarget> compatibleWith,
      Platform targetPlatform) {
    return new Incompatible(target, compatibleWith, targetPlatform);
  }

  /** Constructor to create object for a compatible target node. */
  public static TargetNodeMaybeIncompatible ofCompatible(TargetNode<?> targetNode) {
    return new Compatible(targetNode);
  }

  /** Get target node or throw exception if it is "incompatible". */
  public abstract TargetNode<?> assertGetTargetNode(DependencyStack dependencyStack);

  public abstract Optional<TargetNode<?>> getTargetNodeOptional();

  public abstract BuildTarget getBuildTarget();
}
