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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Predicate;

/** Helpers for reading properties of Apple target build rules. */
public final class AppleBuildRules {

  private static final Logger LOG = Logger.get(AppleBuildRules.class);

  // Utility class not to be instantiated.
  private AppleBuildRules() {}

  private static final ImmutableSet<Class<? extends BuildRule>> XCODE_TARGET_BUILD_RULE_TEST_TYPES =
      ImmutableSet.of(AppleTest.class);

  private static final ImmutableSet<AppleBundleExtension> XCODE_TARGET_TEST_BUNDLE_EXTENSIONS =
      ImmutableSet.of(AppleBundleExtension.XCTEST);

  private static final ImmutableSet<Class<? extends BaseDescription<?>>>
      WRAPPER_RESOURCE_DESCRIPTION_CLASSES =
          ImmutableSet.of(CoreDataModelDescription.class, SceneKitAssetsDescription.class);

  private static final ImmutableSet<Class<? extends BaseDescription<?>>>
      APPLE_ASSET_CATALOG_DESCRIPTION_CLASSES = ImmutableSet.of(AppleAssetCatalogDescription.class);

  public static final ImmutableSet<Class<? extends BaseDescription<?>>>
      CORE_DATA_MODEL_DESCRIPTION_CLASSES = ImmutableSet.of(CoreDataModelDescription.class);

  public static final ImmutableSet<Class<? extends BaseDescription<?>>>
      SCENEKIT_ASSETS_DESCRIPTION_CLASSES = ImmutableSet.of(SceneKitAssetsDescription.class);

  /** Whether the build rule type is a test target. */
  public static boolean isXcodeTargetTestBuildRule(BuildRule rule) {
    return XCODE_TARGET_BUILD_RULE_TEST_TYPES.contains(rule.getClass());
  }

  /** Whether the bundle extension is a test bundle extension. */
  public static boolean isXcodeTargetTestBundleExtension(AppleBundleExtension extension) {
    return XCODE_TARGET_TEST_BUNDLE_EXTENSIONS.contains(extension);
  }

  public static String getOutputFileNameFormatForLibrary(boolean isSharedLibrary) {
    if (isSharedLibrary) {
      return "lib%s.dylib";
    } else {
      return "lib%s.a";
    }
  }

  public enum RecursiveDependenciesMode {
    /** Will traverse all rules that are built. */
    BUILDING,
    /**
     * Will also not traverse the dependencies of bundles, as those are copied inside the bundle.
     */
    COPYING,

    /**
     * Will traverse the dependencies of #shared libraries since the library may not be contained in
     * a bundle.
     */
    COPYING_INCLUDE_SHARED_RESOURCES,

    /** Will also not traverse the dependencies of shared libraries, as those are linked already. */
    LINKING,
  }

  public static ImmutableSet<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      RecursiveDependenciesMode mode,
      TargetNode<?> targetNode,
      Optional<ImmutableSet<Class<? extends BaseDescription<?>>>> descriptionClasses) {
    LOG.verbose(
        "Getting recursive dependencies of node %s, mode %s, including only types %s\n",
        targetNode, mode, descriptionClasses);
    Predicate<TargetNode<?>> isDependencyNode =
        descriptionClasses
            .map(
                classes ->
                    (Predicate<TargetNode<?>>)
                        node -> classes.contains(node.getDescription().getClass()))
            .orElse(x -> true);

    ImmutableSet<TargetNode<?>> result =
        getRecursiveTargetNodeDependenciesOfTypes(
            xcodeDescriptions,
            targetGraph,
            cache,
            mode,
            targetNode,
            isDependencyNode,
            Optional.empty());

    LOG.verbose(
        "Got recursive dependencies of node %s mode %s types %s: %s\n",
        targetNode, mode, descriptionClasses, result);

    return result;
  }

  private static boolean shouldStopRecursiveDependenciesTraversalAtNodeType(
      BaseDescription<?> description) {
    return description instanceof AppleBundleDescription
        || description instanceof AppleResourceDescription;
  }

  public static ImmutableSet<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      RecursiveDependenciesMode mode,
      TargetNode<?> targetNode,
      Predicate<TargetNode<?>> isDependencyNode,
      Optional<AppleCxxPlatform> appleCxxPlatform) {

    @SuppressWarnings("unchecked")
    GraphTraversable<TargetNode<?>> graphTraversable =
        node -> {
          // Handle resource exporting behavior of AppleResource.getResourceFromDeps
          Optional<Iterable<TargetNode<?>>> iterable =
              TargetNodes.castArg(node, AppleResourceDescriptionArg.class)
                  .map(TargetNode::getConstructorArg)
                  .map(arg -> targetGraph.getAll(arg.getResourcesFromDeps()));
          if (iterable.isPresent()) {
            return iterable.get().iterator();
          }

          // Stop traversal for rules outside the specific set.
          if (!xcodeDescriptions.isXcodeDescription(node.getDescription())) {
            return Collections.emptyIterator();
          }

          LOG.verbose("Finding children of node: %s", node);

          ImmutableSortedSet<TargetNode<?>> defaultDeps;
          // Subset of dependencies from the "exported_deps" field.
          ImmutableSortedSet<TargetNode<?>> exportedDeps;
          if (!cache.isPresent()) {
            ImmutableSortedSet.Builder<TargetNode<?>> defaultDepsBuilder =
                ImmutableSortedSet.naturalOrder();
            ImmutableSortedSet.Builder<TargetNode<?>> exportedDepsBuilder =
                ImmutableSortedSet.naturalOrder();
            addDirectAndExportedDeps(
                targetGraph, node, defaultDepsBuilder, exportedDepsBuilder, appleCxxPlatform);
            defaultDeps = defaultDepsBuilder.build();
            exportedDeps = exportedDepsBuilder.build();
          } else {
            defaultDeps = cache.get().getDefaultDeps(node);
            exportedDeps = cache.get().getExportedDeps(node);
          }

          if (node.getDescription() instanceof AppleBundleDescription) {
            AppleBundleDescriptionArg arg = (AppleBundleDescriptionArg) node.getConstructorArg();

            ImmutableSortedSet.Builder<TargetNode<?>> editedDeps =
                ImmutableSortedSet.naturalOrder();
            ImmutableSortedSet.Builder<TargetNode<?>> editedExportedDeps =
                ImmutableSortedSet.naturalOrder();
            ImmutableSortedSet<BuildTarget> binaryTargets = arg.getBinaryTargets();
            for (TargetNode<?> rule : defaultDeps) {
              if (binaryTargets.contains(rule.getBuildTarget())) {
                addDirectAndExportedDeps(
                    targetGraph,
                    targetGraph.get(rule.getBuildTarget()),
                    editedDeps,
                    editedExportedDeps,
                    appleCxxPlatform);
              } else {
                editedDeps.add(rule);
              }
            }

            ImmutableSortedSet<TargetNode<?>> newDefaultDeps = editedDeps.build();
            ImmutableSortedSet<TargetNode<?>> newExportedDeps = editedExportedDeps.build();
            LOG.verbose(
                "Transformed deps for bundle %s: %s -> %s, exported deps %s -> %s",
                node, defaultDeps, newDefaultDeps, exportedDeps, newExportedDeps);
            defaultDeps = newDefaultDeps;
            exportedDeps = newExportedDeps;
          }

          LOG.verbose("Default deps for node %s mode %s: %s", node, mode, defaultDeps);
          if (!exportedDeps.isEmpty()) {
            LOG.verbose("Exported deps for node %s mode %s: %s", node, mode, exportedDeps);
          }

          ImmutableSortedSet<TargetNode<?>> deps = ImmutableSortedSet.of();

          if (node != targetNode) {
            switch (mode) {
              case LINKING:
                {
                  boolean nodeIsAppleLibrary =
                      node.getDescription() instanceof AppleLibraryDescription;
                  boolean nodeIsCxxLibrary = node.getDescription() instanceof CxxLibraryDescription;
                  if (nodeIsAppleLibrary || nodeIsCxxLibrary) {
                    if (AppleLibraryDescription.isNotStaticallyLinkedLibraryNode(
                        (TargetNode<CxxLibraryDescription.CommonArg>) node)) {
                      deps = exportedDeps;
                    } else {
                      deps = defaultDeps;
                    }
                  } else if (shouldStopRecursiveDependenciesTraversalAtNodeType(
                      node.getDescription())) {
                    // TODO(yiding): This seems unnecessary as descriptions matching the predicate
                    // do not have exported deps in the first place.
                    deps = exportedDeps;
                  } else {
                    deps = defaultDeps;
                  }
                  break;
                }
              case COPYING:
              case COPYING_INCLUDE_SHARED_RESOURCES:
                {
                  // When traversing dependencies for resources, we get resources attached to
                  // libraries that are statically linked, and resources attached to the initial
                  // bundle. This heuristic is based on the idea that the bundle holding the
                  // compiled code of a library also holds the resources.

                  // When the resource is dynamically linked (#shared) - it may be contained within
                  // a framework or it may be a standalone .dylib. In the latter case we want these
                  // resources to still be included in the app binary, so give the option to
                  // override
                  // the default behavior - this is really only needed for Xcode project since BUCK
                  // doesn't correctly support embeddedFramework targets.
                  boolean nodeIsAppleLibrary =
                      node.getDescription() instanceof AppleLibraryDescription;
                  boolean nodeIsCxxLibrary = node.getDescription() instanceof CxxLibraryDescription;
                  if (nodeIsAppleLibrary || nodeIsCxxLibrary) {
                    if (mode == RecursiveDependenciesMode.COPYING_INCLUDE_SHARED_RESOURCES
                        || !AppleLibraryDescription.isNotStaticallyLinkedLibraryNode(
                            (TargetNode<CxxLibraryDescription.CommonArg>) node)) {
                      deps = defaultDeps;
                    }

                  } else if (!shouldStopRecursiveDependenciesTraversalAtNodeType(
                      node.getDescription())) {
                    deps = defaultDeps;
                  }
                  break;
                }
              case BUILDING:
                deps = defaultDeps;
                break;
            }
          } else {
            deps = defaultDeps;
          }

          LOG.verbose("Walking children of node %s: %s", node, deps);
          return deps.iterator();
        };

    ImmutableSet.Builder<TargetNode<?>> filteredRules = ImmutableSet.builder();
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable);
    try {
      for (TargetNode<?> node : traversal.traverse(ImmutableList.of(targetNode))) {
        if (node != targetNode && isDependencyNode.test(node)) {
          filteredRules.add(node);
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      // actual load failures and cycle exceptions should have been caught at an earlier stage
      throw new RuntimeException(e);
    }

    return filteredRules.build();
  }

  public static ImmutableSet<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      RecursiveDependenciesMode mode,
      TargetNode<?> input,
      ImmutableSet<Class<? extends BaseDescription<?>>> descriptionClasses) {
    return getRecursiveTargetNodeDependenciesOfTypes(
        xcodeDescriptions, targetGraph, cache, mode, input, Optional.of(descriptionClasses));
  }

  static void addDirectAndExportedDeps(
      TargetGraph targetGraph,
      TargetNode<?> targetNode,
      ImmutableSortedSet.Builder<TargetNode<?>> directDepsBuilder,
      ImmutableSortedSet.Builder<TargetNode<?>> exportedDepsBuilder,
      Optional<AppleCxxPlatform> appleCxxPlatform) {
    directDepsBuilder.addAll(targetGraph.getAll(targetNode.getBuildDeps()));
    if (targetNode.getDescription() instanceof AppleLibraryDescription
        || targetNode.getDescription() instanceof CxxLibraryDescription) {
      CxxLibraryDescription.CommonArg arg =
          (CxxLibraryDescription.CommonArg) targetNode.getConstructorArg();
      LOG.verbose("Exported deps of node %s: %s", targetNode, arg.getExportedDeps());
      Iterable<TargetNode<?>> exportedNodes = targetGraph.getAll(arg.getExportedDeps());
      directDepsBuilder.addAll(exportedNodes);
      exportedDepsBuilder.addAll(exportedNodes);
    }
    // apple bundle targets have target only dependencies on binary/platform_binary one of which
    // will end up being a build time dependency. Since at target graph construction time it's not
    // clear which one will end up being a build dependency, include both.
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      AppleBundleDescriptionArg arg = (AppleBundleDescriptionArg) targetNode.getConstructorArg();
      directDepsBuilder.addAll(targetGraph.getAll(arg.getBinaryTargets()));
    }
    // if target platform is known, we should discover targets that match it to make sure that
    // all resources are properly discovered and added to the final apple_bundle
    if (appleCxxPlatform.isPresent()
        && targetNode.getDescription() instanceof AppleLibraryDescription) {
      AppleLibraryDescriptionArg arg = (AppleLibraryDescriptionArg) targetNode.getConstructorArg();
      for (ImmutableSortedSet<BuildTarget> deps :
          arg.getPlatformDeps().getMatchingValues(appleCxxPlatform.get().getFlavor().toString())) {
        directDepsBuilder.addAll(targetGraph.getAll(deps));
      }
    }
  }

  public static ImmutableSet<TargetNode<?>> getSchemeBuildableTargetNodes(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      TargetNode<?> targetNode) {
    Iterable<TargetNode<?>> targetNodes =
        Iterables.concat(
            getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                cache,
                RecursiveDependenciesMode.BUILDING,
                targetNode,
                Optional.empty()),
            ImmutableSet.of(targetNode));

    return RichStream.from(targetNodes)
        .filter(input -> xcodeDescriptions.isXcodeDescription(input.getDescription()))
        .toImmutableSet();
  }

  public static <T> ImmutableSet<AppleAssetCatalogDescriptionArg> collectRecursiveAssetCatalogs(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      Iterable<TargetNode<T>> targetNodes,
      RecursiveDependenciesMode mode) {
    return RichStream.from(targetNodes)
        .flatMap(
            input ->
                getRecursiveTargetNodeDependenciesOfTypes(
                        xcodeDescriptions,
                        targetGraph,
                        cache,
                        mode,
                        input,
                        APPLE_ASSET_CATALOG_DESCRIPTION_CLASSES)
                    .stream())
        .map(input -> (AppleAssetCatalogDescriptionArg) input.getConstructorArg())
        .toImmutableSet();
  }

  public static <T> ImmutableSet<AppleWrapperResourceArg> collectRecursiveWrapperResources(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      Iterable<TargetNode<T>> targetNodes,
      RecursiveDependenciesMode mode) {

    return RichStream.from(targetNodes)
        .flatMap(
            input ->
                getRecursiveTargetNodeDependenciesOfTypes(
                        xcodeDescriptions,
                        targetGraph,
                        cache,
                        mode,
                        input,
                        WRAPPER_RESOURCE_DESCRIPTION_CLASSES)
                    .stream())
        .map(input -> (AppleWrapperResourceArg) input.getConstructorArg())
        .toImmutableSet();
  }

  @SuppressWarnings("unchecked")
  public static <T> ImmutableSet<T> collectTransitiveBuildRules(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Optional<AppleDependenciesCache> cache,
      ImmutableSet<Class<? extends BaseDescription<?>>> descriptionClasses,
      Collection<TargetNode<?>> targetNodes,
      RecursiveDependenciesMode mode) {

    return RichStream.from(targetNodes)
        .flatMap(
            targetNode ->
                getRecursiveTargetNodeDependenciesOfTypes(
                        xcodeDescriptions, targetGraph, cache, mode, targetNode, descriptionClasses)
                    .stream())
        .map(input -> (T) input.getConstructorArg())
        .toImmutableSet();
  }

  public static ImmutableSet<AppleAssetCatalogDescriptionArg> collectDirectAssetCatalogs(
      TargetGraph targetGraph, TargetNode<?> targetNode) {
    return RichStream.from(targetGraph.getAll(targetNode.getBuildDeps()))
        .filter(node -> node.getDescription() instanceof AppleAssetCatalogDescription)
        .map(node -> (AppleAssetCatalogDescriptionArg) node.getConstructorArg())
        .toImmutableSet();
  }
}
