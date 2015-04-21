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

import com.facebook.buck.rules.coercer.AppleBundleDestination;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

import java.util.Set;

public class AppleResources {
  // Utility class, do not instantiate.
  private AppleResources() { }

  private static final Function<Object, AppleBundleDestination> APPLE_BUNDLE_DESTINATION_RESOURCE =
      Functions.constant(
          AppleBundleDestination.of(
              AppleBundleDestination.SubfolderSpec.RESOURCES,
              Optional.<String>absent()));

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

  public static void addResourceDirsToBuilder(
      ImmutableMap.Builder<Path, AppleBundleDestination> resourceDirsBuilder,
      Iterable<AppleResourceDescription.Arg> resourceDescriptions) {
    resourceDirsBuilder.putAll(
        FluentIterable
            .from(resourceDescriptions)
            .transformAndConcat(
                new Function<AppleResourceDescription.Arg, Set<Path>>() {
                  @Override
                  public Set<Path> apply(AppleResourceDescription.Arg arg) {
                    return arg.dirs;
                  }
                })
            .toMap(APPLE_BUNDLE_DESTINATION_RESOURCE)
    );
  }

  public static void addResourceFilesToBuilder(
      ImmutableMap.Builder<SourcePath, AppleBundleDestination> resourceFilesBuilder,
      Iterable<AppleResourceDescription.Arg> resourceDescriptions) {
    resourceFilesBuilder.putAll(
        FluentIterable
            .from(resourceDescriptions)
            .transformAndConcat(
                new Function<AppleResourceDescription.Arg, Set<SourcePath>>() {
                  @Override
                  public Set<SourcePath> apply(AppleResourceDescription.Arg arg) {
                    return arg.files;
                  }
                })
            .toMap(APPLE_BUNDLE_DESTINATION_RESOURCE)
    );
  }
}
