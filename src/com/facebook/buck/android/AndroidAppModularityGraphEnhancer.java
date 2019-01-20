/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.SortedSet;

public class AndroidAppModularityGraphEnhancer {

  private final BuildTarget originalBuildTarget;
  private final SortedSet<BuildRule> originalDeps;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final APKModuleGraph apkModuleGraph;

  AndroidAppModularityGraphEnhancer(
      BuildTarget buildTarget,
      BuildRuleParams originalParams,
      BuildRuleResolver ruleResolver,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      APKModuleGraph apkModuleGraph) {
    this.originalBuildTarget = buildTarget;
    this.originalDeps = originalParams.getBuildDeps();
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.apkModuleGraph = apkModuleGraph;
  }

  AndroidAppModularityGraphEnhancementResult createAdditionalBuildables() {

    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget, buildTargetsToExcludeFromDex, ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(originalDeps), ruleResolver);
    AndroidPackageableCollection packageableCollection = collector.build();

    enhancedDeps.addAll(getTargetsAsRules(packageableCollection.getJavaLibrariesToDex()));

    // Add dependencies on all the build rules generating third-party JARs.  This is mainly to
    // correctly capture deps when a prebuilt_jar forwards the output from another build rule.
    enhancedDeps.addAll(
        ruleFinder.filterBuildRuleInputs(
            packageableCollection
                .getPathsToThirdPartyJars()
                .get(apkModuleGraph.getRootAPKModule())));

    return AndroidAppModularityGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setFinalDeps(enhancedDeps.build())
        .setAPKModuleGraph(apkModuleGraph)
        .build();
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(originalBuildTarget, ruleResolver, buildTargets);
  }
}
