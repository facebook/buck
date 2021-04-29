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

package com.facebook.buck.apple;

import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxResourceName;
import com.facebook.buck.cxx.CxxResourceUtils;
import com.facebook.buck.file.CopyTree;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Optional;
import java.util.function.Predicate;

public class AppleResources {

  @VisibleForTesting
  public static final Predicate<TargetNode<?>> IS_APPLE_BUNDLE_RESOURCE_NODE =
      node -> node.getDescription() instanceof HasAppleBundleResourcesDescription;

  public static final Predicate<TargetNode<?>> IS_CXX_LIBRARY_NODE =
      node -> node.getDescription() instanceof CxxLibraryDescription;

  // Utility class, do not instantiate.
  private AppleResources() {}

  /**
   * Collect resources from recursive dependencies.
   *
   * @param targetGraph The {@link TargetGraph} containing the node and its dependencies.
   * @param targetNode {@link TargetNode} at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  public static ImmutableSet<AppleResourceDescriptionArg> collectRecursiveResources(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      TargetNode<?> targetNode,
      RecursiveDependenciesMode mode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                cache,
                mode,
                targetNode,
                ImmutableSet.of(AppleResourceDescription.class)))
        .transform(input -> (AppleResourceDescriptionArg) input.getConstructorArg())
        .toSet();
  }

  // Embed C++ resources from C++ library dependencies into a "CxxResources" in the bundle.
  private static void collectCxxLibraryResources(
      ActionGraphBuilder resolver, TargetNode<?> node, AppleBundleResources.Builder builder) {
    CxxLibraryDescription description = (CxxLibraryDescription) node.getDescription();
    CxxLibraryDescriptionArg arg =
        description.getConstructorArgType().cast(node.getConstructorArg());
    if (!arg.getResources().isEmpty()) {
      ForwardRelPath cxxResRoot = ForwardRelPath.of("CxxResources");
      CopyTree resourceTree =
          (CopyTree)
              resolver.computeIfAbsent(
                  node.getBuildTarget()
                      .withAppendedFlavors(InternalFlavor.of("apple-cxx-resources")),
                  target -> {
                    ImmutableMap<CxxResourceName, SourcePath> resources =
                        CxxResourceUtils.fullyQualify(
                            node.getBuildTarget(),
                            arg.getHeaderNamespace(),
                            arg.getResources()
                                .toNameMap(
                                    node.getBuildTarget(),
                                    resolver.getSourcePathResolver(),
                                    "resources"));
                    return new CopyTree(
                        target,
                        node.getFilesystem(),
                        resolver,
                        ImmutableSortedMap.copyOf(
                            MoreMaps.transformKeys(
                                resources, name -> cxxResRoot.resolve(name.getNameAsPath()))));
                  });
      builder.addResourceDirs(
          SourcePathWithAppleBundleDestination.of(
              // Apple resources are copied in using the file name of the source path, so remap
              // the source path so that we specify the resource root base used for C++
              // resources embedded in Apple bundles.
              ExplicitBuildTargetSourcePath.of(
                  resourceTree.getBuildTarget(),
                  resolver
                      .getSourcePathResolver()
                      .getCellUnsafeRelPath(resourceTree.getSourcePathToOutput())
                      .resolve(cxxResRoot.toPath(node.getFilesystem().getFileSystem()))),
              AppleBundleDestination.RESOURCES));
    }
  }

  /** Collect resource dirs and files */
  public static <T extends ConstructorArg> AppleBundleResources collectResourceDirsAndFiles(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      ActionGraphBuilder resolver,
      Optional<AppleDependenciesCache> cache,
      TargetNode<T> targetNode,
      AppleCxxPlatform appleCxxPlatform,
      RecursiveDependenciesMode mode,
      Predicate<BuildTarget> filter) {
    AppleBundleResources.Builder builder = AppleBundleResources.builder();

    Iterable<TargetNode<?>> resourceNodes =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            xcodeDescriptions,
            targetGraph,
            cache,
            mode,
            targetNode,
            IS_APPLE_BUNDLE_RESOURCE_NODE.or(IS_CXX_LIBRARY_NODE),
            Optional.of(appleCxxPlatform));
    ProjectFilesystem filesystem = targetNode.getFilesystem();

    for (TargetNode<?> resourceNode : resourceNodes) {
      if (!filter.test(resourceNode.getBuildTarget())) {
        continue;
      }

      if (resourceNode.getDescription() instanceof CxxLibraryDescription) {
        collectCxxLibraryResources(resolver, resourceNode, builder);
        continue;
      }

      @SuppressWarnings("unchecked")
      TargetNode<BuildRuleArg> node = (TargetNode<BuildRuleArg>) resourceNode;

      @SuppressWarnings("unchecked")
      HasAppleBundleResourcesDescription<BuildRuleArg> description =
          (HasAppleBundleResourcesDescription<BuildRuleArg>) node.getDescription();
      description.addAppleBundleResources(builder, node, filesystem, resolver);
    }
    return builder.build();
  }

  public static ImmutableSet<AppleResourceDescriptionArg> collectDirectResources(
      TargetGraph targetGraph, TargetNode<?> targetNode) {
    ImmutableSet.Builder<AppleResourceDescriptionArg> builder = ImmutableSet.builder();
    Iterable<TargetNode<?>> deps = targetGraph.getAll(targetNode.getBuildDeps());
    for (TargetNode<?> node : deps) {
      if (node.getDescription() instanceof AppleResourceDescription) {
        builder.add((AppleResourceDescriptionArg) node.getConstructorArg());
      }
    }
    return builder.build();
  }
}
