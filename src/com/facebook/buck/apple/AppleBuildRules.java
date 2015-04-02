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

import com.facebook.buck.log.Logger;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.util.Iterator;

import javax.annotation.Nullable;

/**
 * Helpers for reading properties of Apple target build rules.
 */
public final class AppleBuildRules {

  private static final Logger LOG = Logger.get(AppleBuildRules.class);

  // Utility class not to be instantiated.
  private AppleBuildRules() { }

  public static final ImmutableSet<BuildRuleType> XCODE_TARGET_BUILD_RULE_TYPES =
      ImmutableSet.of(
          AppleLibraryDescription.TYPE,
          AppleBinaryDescription.TYPE,
          AppleBundleDescription.TYPE,
          AppleTestDescription.TYPE);

  private static final ImmutableSet<BuildRuleType> XCODE_TARGET_BUILD_RULE_TEST_TYPES =
      ImmutableSet.of(AppleTestDescription.TYPE);

  private static final ImmutableSet<AppleBundleExtension> XCODE_TARGET_TEST_BUNDLE_EXTENSIONS =
      ImmutableSet.of(AppleBundleExtension.OCTEST, AppleBundleExtension.XCTEST);

  /**
   * Whether the build rule type is equivalent to some kind of Xcode target.
   */
  public static boolean isXcodeTargetBuildRuleType(@Nullable BuildRuleType type) {
    return XCODE_TARGET_BUILD_RULE_TYPES.contains(type);
  }

  /**
   * Whether the build rule type is a test target.
   */
  public static boolean isXcodeTargetTestBuildRule(BuildRule rule) {
    return XCODE_TARGET_BUILD_RULE_TEST_TYPES.contains(rule.getType());
  }

  /**
   * Whether the bundle extension is a test bundle extension.
   */
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
    /**
     * Will traverse all rules that are built.
     */
    BUILDING,
    /**
     * Will also not traverse the dependencies of bundles, as those are copied inside the bundle.
     */
    COPYING,
    /**
     * Will also not traverse the dependencies of shared libraries, as those are linked already.
     */
    LINKING,
  }

  public static Iterable<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      final TargetGraph targetGraph,
      final RecursiveDependenciesMode mode,
      final TargetNode<?> targetNode,
      final Optional<ImmutableSet<BuildRuleType>> types) {
    LOG.verbose(
        "Getting recursive dependencies of node %s, mode %s, including only types %s\n",
        targetNode,
        mode,
        types);
    final ImmutableList.Builder<TargetNode<?>> filteredRules = ImmutableList.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<TargetNode<?>>() {
          @Override
          protected Iterator<TargetNode<?>> findChildren(TargetNode<?> node) throws IOException {
            LOG.verbose("Finding children of node: %s", node);
            ImmutableSortedSet.Builder<TargetNode<?>> defaultDepsBuilder =
              ImmutableSortedSet.naturalOrder();
            ImmutableSortedSet.Builder<TargetNode<?>> exportedDepsBuilder =
              ImmutableSortedSet.naturalOrder();
            addDirectAndExportedDeps(targetGraph, node, defaultDepsBuilder, exportedDepsBuilder);
            ImmutableSortedSet<TargetNode<?>> defaultDeps = defaultDepsBuilder.build();
            ImmutableSortedSet<TargetNode<?>> exportedDeps = exportedDepsBuilder.build();

            if (node.getType().equals(AppleBundleDescription.TYPE)) {
              AppleBundleDescription.Arg arg =
                  (AppleBundleDescription.Arg) node.getConstructorArg();

              ImmutableSortedSet.Builder<TargetNode<?>> editedDeps =
                  ImmutableSortedSet.naturalOrder();
              ImmutableSortedSet.Builder<TargetNode<?>> editedExportedDeps =
                  ImmutableSortedSet.naturalOrder();
              for (TargetNode<?> rule : defaultDeps) {
                if (!rule.getBuildTarget().equals(arg.binary)) {
                  editedDeps.add(rule);
                } else {
                  addDirectAndExportedDeps(
                      targetGraph,
                      targetGraph.get(arg.binary),
                      editedDeps,
                      editedExportedDeps);
                }
              }

              ImmutableSortedSet<TargetNode<?>> newDefaultDeps = editedDeps.build();
              ImmutableSortedSet<TargetNode<?>> newExportedDeps = editedExportedDeps.build();
              LOG.verbose(
                  "Transformed deps for bundle %s: %s -> %s, exported deps %s -> %s",
                  node,
                  defaultDeps,
                  newDefaultDeps,
                  exportedDeps,
                  newExportedDeps);
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
                  if (node.getType().equals(AppleLibraryDescription.TYPE)) {
                    if (AppleLibraryDescription.isSharedLibraryTarget(node.getBuildTarget())) {
                      deps = exportedDeps;
                    } else {
                      deps = defaultDeps;
                    }
                  } else if (node.getType().equals(AppleBundleDescription.TYPE)) {
                    deps = exportedDeps;
                  } else {
                    deps = defaultDeps;
                  }
                  break;
                case COPYING:
                  if (node.getType().equals(AppleBundleDescription.TYPE)) {
                    deps = exportedDeps;
                  } else {
                    deps = defaultDeps;
                  }
                  break;
                case BUILDING:
                  deps = defaultDeps;
                  break;
              }
            } else {
              deps = defaultDeps;
            }

            LOG.verbose("Walking children of node %s: %s", node, deps);
            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(TargetNode<?> node) {
            if (node != targetNode &&
                (!types.isPresent() || types.get().contains(node.getType()))) {
              filteredRules.add(node);
            }
          }

          @Override
          protected void onTraversalComplete(Iterable<TargetNode<?>> nodesInExplorationOrder) {
          }
        };
    try {
      traversal.traverse(ImmutableList.of(targetNode));
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException | IOException |
        InterruptedException e) {
      // actual load failures and cycle exceptions should have been caught at an earlier stage
      throw new RuntimeException(e);
    }
    ImmutableList<TargetNode<?>> result = filteredRules.build();
    LOG.verbose(
        "Got recursive dependencies of node %s mode %s types %s: %s\n",
        targetNode,
        mode,
        types,
        result);

    return result;
  }

  private static void addDirectAndExportedDeps(
      TargetGraph targetGraph,
      TargetNode<?> targetNode,
      ImmutableSortedSet.Builder<TargetNode<?>> directDepsBuilder,
      ImmutableSortedSet.Builder<TargetNode<?>> exportedDepsBuilder
  ) {
    directDepsBuilder.addAll(targetGraph.getAll(targetNode.getDeps()));
    if (targetNode.getType() == AppleLibraryDescription.TYPE) {
      AppleNativeTargetDescriptionArg arg =
          (AppleNativeTargetDescriptionArg) targetNode.getConstructorArg();
      LOG.verbose("Exported deps of node %s: %s", targetNode, arg.exportedDeps.get());
      Iterable<TargetNode<?>> exportedNodes = targetGraph.getAll(arg.exportedDeps.get());
      directDepsBuilder.addAll(exportedNodes);
      exportedDepsBuilder.addAll(exportedNodes);
    }
  }

  public static ImmutableSet<TargetNode<?>> getSchemeBuildableTargetNodes(
      TargetGraph targetGraph,
      TargetNode<?> targetNode) {
    Iterable<TargetNode<?>> targetNodes = Iterables.concat(
        getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.<ImmutableSet<BuildRuleType>>absent()),
        ImmutableSet.of(targetNode));

    return ImmutableSet.copyOf(
        Iterables.filter(
            targetNodes,
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return isXcodeTargetBuildRuleType(input.getType());
              }
            }));
  }

  /**
   * Given a list of nodes, return AppleTest nodes that can be grouped with other tests.
   */
  public static ImmutableSet<TargetNode<AppleTestDescription.Arg>> filterGroupableTests(
      Iterable<TargetNode<?>> tests) {
    ImmutableSet.Builder<TargetNode<AppleTestDescription.Arg>> builder = ImmutableSet.builder();
    for (TargetNode<?> node : tests) {
      Optional<TargetNode<AppleTestDescription.Arg>> testNode =
          node.castArg(AppleTestDescription.Arg.class);
      if (testNode.isPresent() && testNode.get().getConstructorArg().canGroup()) {
        builder.add(testNode.get());
      }
    }
    return builder.build();
  }

  public static Function<TargetNode<?>, Iterable<TargetNode<?>>>
    newRecursiveRuleDependencyTransformer(
      final TargetGraph targetGraph,
      final RecursiveDependenciesMode mode,
      final ImmutableSet<BuildRuleType> types) {
    return new Function<TargetNode<?>, Iterable<TargetNode<?>>>() {
      @Override
      public Iterable<TargetNode<?>> apply(TargetNode<?> input) {
        return getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            mode,
            input,
            Optional.of(types));
      }
    };
  }

}
