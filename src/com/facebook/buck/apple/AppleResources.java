/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.ReactNativeBundle;
import com.facebook.buck.js.ReactNativeLibraryArgs;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import java.util.Optional;

public class AppleResources {

  private static final BuildRuleType APPLE_RESOURCE_RULE_TYPE =
      Description.getBuildRuleType(AppleResourceDescription.class);

  private static final ImmutableSet<BuildRuleType> APPLE_RESOURCE_RULE_TYPES =
      ImmutableSet.of(
          Description.getBuildRuleType(AppleResourceDescription.class),
          Description.getBuildRuleType(IosReactNativeLibraryDescription.class));

  // Utility class, do not instantiate.
  private AppleResources() { }

  /**
   * Collect resources from recursive dependencies.
   *
   * @param targetGraph The {@link TargetGraph} containing the node and its dependencies.
   * @param targetNodes {@link TargetNode} at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  public static ImmutableSet<AppleResourceDescription.Arg> collectRecursiveResources(
      final TargetGraph targetGraph,
      final Optional<AppleDependenciesCache> cache,
      Iterable<? extends TargetNode<?, ?>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                cache,
                AppleBuildRules.RecursiveDependenciesMode.COPYING,
                ImmutableSet.of(APPLE_RESOURCE_RULE_TYPE)))
        .transform(
            input -> (AppleResourceDescription.Arg) input.getConstructorArg())
        .toSet();
  }

  public static <T> AppleBundleResources collectResourceDirsAndFiles(
      final TargetGraph targetGraph,
      final Optional<AppleDependenciesCache> cache,
      TargetNode<T, ?> targetNode) {
    AppleBundleResources.Builder builder = AppleBundleResources.builder();

    Iterable<TargetNode<?, ?>> resourceNodes =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            cache,
            AppleBuildRules.RecursiveDependenciesMode.COPYING,
            targetNode,
            Optional.of(APPLE_RESOURCE_RULE_TYPES));

    ProjectFilesystem filesystem = targetNode.getFilesystem();

    for (TargetNode<?, ?> resourceNode : resourceNodes) {
      Object constructorArg = resourceNode.getConstructorArg();
      if (constructorArg instanceof AppleResourceDescription.Arg) {
        AppleResourceDescription.Arg appleResource = (AppleResourceDescription.Arg) constructorArg;
        builder.addAllResourceDirs(appleResource.dirs);
        builder.addAllResourceFiles(appleResource.files);
        builder.addAllResourceVariantFiles(appleResource.variants);
      } else {
        Preconditions.checkState(constructorArg instanceof ReactNativeLibraryArgs);
        BuildTarget buildTarget = resourceNode.getBuildTarget();
        builder.addDirsContainingResourceDirs(
            new BuildTargetSourcePath(
                buildTarget,
                ReactNativeBundle.getPathToJSBundleDir(buildTarget, filesystem)),
            new BuildTargetSourcePath(
                buildTarget,
                ReactNativeBundle.getPathToResources(buildTarget, filesystem)));
      }
    }
    return builder.build();
  }

  public static ImmutableSet<AppleResourceDescription.Arg> collectDirectResources(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode) {
    ImmutableSet.Builder<AppleResourceDescription.Arg> builder = ImmutableSet.builder();
    Iterable<TargetNode<?, ?>> deps = targetGraph.getAll(targetNode.getDeps());
    for (TargetNode<?, ?> node : deps) {
      if (node.getType().equals(APPLE_RESOURCE_RULE_TYPE)) {
        builder.add((AppleResourceDescription.Arg) node.getConstructorArg());
      }
    }
    return builder.build();
  }

}
