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
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
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

  private static final ImmutableSet<BuildRuleType> XCODE_TARGET_BUILD_RULE_TYPES =
      ImmutableSet.of(
          AppleLibraryDescription.TYPE,
          AppleBinaryDescription.TYPE,
          AppleBundleDescription.TYPE);

  private static final ImmutableSet<BuildRuleType> XCODE_TARGET_BUILD_RULE_TEST_TYPES =
      ImmutableSet.of(AppleTestDescription.TYPE);

  private static final ImmutableSet<AppleBundleExtension> XCODE_TARGET_TEST_BUNDLE_EXTENSIONS =
      ImmutableSet.of(AppleBundleExtension.OCTEST, AppleBundleExtension.XCTEST);

  /**
   * Whether the build rule type is equivalent to some kind of Xcode target.
   */
  public static boolean isXcodeTargetBuildRuleType(BuildRuleType type) {
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

  /**
   * Whether the rule is an AppleBundle rule containing a known test bundle extension.
   */
  public static boolean isXcodeTargetTestBundleBuildRule(BuildRule rule) {
    if (!(rule instanceof AppleBundle)) {
      return false;
    }
    AppleBundle appleBundleRule = (AppleBundle) rule;
    if (!appleBundleRule.getExtensionValue().isPresent()) {
      return false;
    }
    return isXcodeTargetTestBundleExtension(appleBundleRule.getExtensionValue().get());
  }

  public enum RecursiveRuleDependenciesMode {
    /**
     * Will always traverse dependencies.
     */
    COMPLETE,
    /**
     * Will traverse all rules that are built.
     */
    BUILDING,
    /**
     * Will also not traverse the dependencies of bundles, as those are copied inside the bundle.
     */
    COPYING,
    /**
     * Will also not traverse the dependencies of dynamic libraries, as those are linked already.
     */
    LINKING,
  };

  public static Iterable<BuildRule> getRecursiveRuleDependenciesOfType(
      final RecursiveRuleDependenciesMode mode, final BuildRule rule, BuildRuleType... types) {
    return getRecursiveRuleDependenciesOfTypes(mode, rule, Optional.of(ImmutableSet.copyOf(types)));
  }

  public static Iterable<BuildRule> getRecursiveRuleDependenciesOfTypes(
      final RecursiveRuleDependenciesMode mode,
      final BuildRule rule,
      final Optional<ImmutableSet<BuildRuleType>> types) {
    final ImmutableList.Builder<BuildRule> filteredRules = ImmutableList.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule>() {
          @Override
          protected Iterator<BuildRule> findChildren(BuildRule node) throws IOException {
            ImmutableSortedSet<BuildRule> defaultDeps = node.getDeps();

            if (node.getType().equals(AppleBundleDescription.TYPE) &&
                mode != RecursiveRuleDependenciesMode.COMPLETE) {
              AppleBundle bundle = (AppleBundle) node;

              ImmutableSortedSet.Builder<BuildRule> editedDeps = ImmutableSortedSet.naturalOrder();
              for (BuildRule rule : defaultDeps) {
                if (rule != bundle.getBinary()) {
                  editedDeps.add(rule);
                } else {
                  editedDeps.addAll(bundle.getBinary().getDeps());
                }
              }

              defaultDeps = editedDeps.build();
            }

            ImmutableSortedSet<BuildRule> deps;

            if (node != rule) {
              switch (mode) {
                case LINKING:
                  if (node.getType().equals(AppleLibraryDescription.TYPE)) {
                    AppleLibrary library = (AppleLibrary) node;
                    if (library.getLinkedDynamically()) {
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
                //$CASES-OMITTED$
              default:
                  deps = defaultDeps;
                  break;
              }
            } else {
              deps = defaultDeps;
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildRule node) {
            if (node != rule && (!types.isPresent() || types.get().contains(node.getType()))) {
              filteredRules.add(node);
            }
          }

          @Override
          protected void onTraversalComplete(Iterable<BuildRule> nodesInExplorationOrder) {
          }
        };
    try {
      traversal.traverse(ImmutableList.of(rule));
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException | IOException |
        InterruptedException e) {
      // actual load failures and cycle exceptions should have been caught at an earlier stage
      throw new RuntimeException(e);
    }
    return filteredRules.build();
  }

  public static ImmutableSet<BuildRule> getSchemeBuildableRules(BuildRule primaryRule) {
    final Iterable<BuildRule> buildRulesIterable = Iterables.concat(
        getRecursiveRuleDependenciesOfTypes(
            RecursiveRuleDependenciesMode.BUILDING,
            primaryRule,
            Optional.<ImmutableSet<BuildRuleType>>absent()),
        ImmutableSet.of(primaryRule));

    return ImmutableSet.copyOf(
        Iterables.filter(
            buildRulesIterable,
            new Predicate<BuildRule>() {
              @Override
              public boolean apply(@Nullable BuildRule input) {
                if (!isXcodeTargetBuildRuleType(input.getType()) &&
                    XcodeNativeDescription.TYPE != input.getType()) {
                  return false;
                }

                return true;
              }
            }));
  }

  /**
   * Builds the multimap of (source rule: [test rule 1, test rule 2, ...])
   * for the set of test rules covering each source rule.
   */
  public static final ImmutableMultimap<BuildRule, AppleTest> getSourceRuleToTestRulesMap(
      Iterable<BuildRule> testRules) {
    ImmutableMultimap.Builder<BuildRule, AppleTest> sourceRuleToTestRulesBuilder =
      ImmutableMultimap.builder();
    for (BuildRule rule : testRules) {
      if (!isXcodeTargetTestBuildRule(rule)) {
        LOG.verbose("Skipping rule %s (not xcode target test)", rule);
        continue;
      }
      AppleTest testRule = (AppleTest) rule;
      for (BuildRule sourceRule : testRule.getSourceUnderTest()) {
        sourceRuleToTestRulesBuilder.put(sourceRule, testRule);
      }
    }
    return sourceRuleToTestRulesBuilder.build();
  }
}
