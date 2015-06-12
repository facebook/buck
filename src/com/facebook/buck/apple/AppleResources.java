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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

public class AppleResources {
  // Utility class, do not instantiate.
  private AppleResources() { }

  /**
   * Collect resources from recursive dependencies.
   *
   * @param targetGraph The {@link TargetGraph} containing the node and its dependencies.
   * @param targetNodes {@link TargetNode} at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  public static <T> ImmutableSet<AppleResourceDescription.Arg> collectRecursiveResources(
      final TargetGraph targetGraph,
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.COPYING,
                ImmutableSet.of(AppleResourceDescription.TYPE)))
        .transform(
            new Function<TargetNode<?>, AppleResourceDescription.Arg>() {
              @Override
              public AppleResourceDescription.Arg apply(TargetNode<?> input) {
                return (AppleResourceDescription.Arg) input.getConstructorArg();
              }
            })
        .toSet();
  }

  public static <T> void collectResourceDirsAndFiles(
      TargetGraph targetGraph,
      TargetNode<T> targetNode,
      ProjectFilesystem filesystem,
      ImmutableSet.Builder<SourcePath> resourceDirs,
      ImmutableSet.Builder<SourcePath> dirsContainingResourceDirs,
      ImmutableSet.Builder<SourcePath> resourceFiles) {
    Iterable<TargetNode<?>> resourceNodes =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            AppleBuildRules.RecursiveDependenciesMode.COPYING,
            targetNode,
            Optional.of(ImmutableSet.of(
                    AppleResourceDescription.TYPE,
                    IosReactNativeLibraryDescription.TYPE)));

    for (TargetNode<?> resourceNode : resourceNodes) {
      Object constructorArg = resourceNode.getConstructorArg();
      if (constructorArg instanceof AppleResourceDescription.Arg) {
        AppleResourceDescription.Arg appleResource = (AppleResourceDescription.Arg) constructorArg;
        resourceDirs.addAll(appleResource.dirs);
        resourceFiles.addAll(appleResource.files);
      } else {
        Preconditions.checkState(constructorArg instanceof ReactNativeLibraryArgs);
        BuildTarget buildTarget = resourceNode.getBuildTarget();

        dirsContainingResourceDirs.add(
            new BuildTargetSourcePath(
                filesystem,
                buildTarget,
                ReactNativeBundle.getPathToJSBundleDir(buildTarget)),
            new BuildTargetSourcePath(
                filesystem,
                buildTarget,
                ReactNativeBundle.getPathToResources(buildTarget)));
      }
    }
  }
}
