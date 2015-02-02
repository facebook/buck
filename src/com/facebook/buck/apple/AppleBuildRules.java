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

import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
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
   * Whether the target node type is a test target.
   */
  public static boolean isXcodeTargetTestTargetNode(TargetNode<?> targetNode) {
    return XCODE_TARGET_BUILD_RULE_TEST_TYPES.contains(targetNode.getType());
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

  @SuppressWarnings("unchecked")
  public static <T> Iterable<TargetNode<T>> getRecursiveTargetNodeDependenciesOfType(
      TargetGraph targetGraph,
      RecursiveDependenciesMode mode,
      TargetNode<?> targetNode,
      BuildRuleType type) {
    return Iterables.transform(
        getRecursiveTargetNodeDependenciesOfTypes(targetGraph, mode, targetNode, type),
        new Function<TargetNode<?>, TargetNode<T>>() {
          @Override
          public TargetNode<T> apply(TargetNode<?> input) {
            return (TargetNode<T>) input;
          }
        });
  }

  public static Iterable<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      TargetGraph targetGraph,
      RecursiveDependenciesMode mode,
      TargetNode<?> targetNode,
      BuildRuleType... types) {
    return getRecursiveTargetNodeDependenciesOfTypes(
        targetGraph,
        mode,
        targetNode,
        Optional.of(ImmutableSet.copyOf(types)));
  }

  public static Iterable<TargetNode<?>> getRecursiveTargetNodeDependenciesOfTypes(
      final TargetGraph targetGraph,
      final RecursiveDependenciesMode mode,
      final TargetNode<?> targetNode,
      final Optional<ImmutableSet<BuildRuleType>> types) {
    final ImmutableList.Builder<TargetNode<?>> filteredRules = ImmutableList.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<TargetNode<?>>() {
          @Override
          protected Iterator<TargetNode<?>> findChildren(TargetNode<?> node) throws IOException {
            ImmutableSortedSet<TargetNode<?>> defaultDeps = ImmutableSortedSet.copyOf(
                targetGraph.getAll(node.getDeps()));

            if (node.getType().equals(AppleBundleDescription.TYPE)) {
              AppleBundleDescription.Arg arg =
                  (AppleBundleDescription.Arg) node.getConstructorArg();

              ImmutableSortedSet.Builder<TargetNode<?>> editedDeps =
                  ImmutableSortedSet.naturalOrder();
              for (TargetNode<?> rule : defaultDeps) {
                if (!rule.getBuildTarget().equals(arg.binary)) {
                  editedDeps.add(rule);
                } else {
                  editedDeps.addAll(
                      targetGraph.getAll(
                          Preconditions.checkNotNull(targetGraph.get(arg.binary)).getDeps()));
                }
              }

              defaultDeps = editedDeps.build();
            }

            ImmutableSortedSet<TargetNode<?>> deps = ImmutableSortedSet.of();

            if (node != targetNode) {
              switch (mode) {
                case LINKING:
                  if (node.getType().equals(AppleLibraryDescription.TYPE)) {
                    if (AppleLibraryDescription.isSharedLibraryTarget(node.getBuildTarget())) {
                      deps = ImmutableSortedSet.of();
                    } else {
                      deps = defaultDeps;
                    }
                  } else if (node.getType().equals(AppleBundleDescription.TYPE)) {
                    deps = ImmutableSortedSet.of();
                  } else {
                    deps = defaultDeps;
                  }
                  break;
                case COPYING:
                  if (node.getType().equals(AppleBundleDescription.TYPE)) {
                    deps = ImmutableSortedSet.of();
                  } else {
                    deps = defaultDeps;
                  }
                  break;
                case BUILDING:
                  return defaultDeps.iterator();
              }
            } else {
              return defaultDeps.iterator();
            }

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
    return filteredRules.build();
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
}
