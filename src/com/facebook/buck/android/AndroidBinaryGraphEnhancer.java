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

import com.facebook.buck.java.AccumulateClassNames;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildRuleBuilderParams;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidBinaryGraphEnhancer {

  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final ImmutableSet<BuildTarget> buildRulesToExcludeFromDex;
  private final AbstractBuildRuleBuilderParams buildRuleBuilderParams;

  AndroidBinaryGraphEnhancer(
      ImmutableSortedSet<BuildRule> originalDeps,
      ImmutableSet<BuildTarget> buildRulesToExcludeFromDex,
      Function<String, Path> pathRelativizer,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this.originalDeps = Preconditions.checkNotNull(originalDeps);
    this.buildRulesToExcludeFromDex = Preconditions.checkNotNull(buildRulesToExcludeFromDex);
    this.buildRuleBuilderParams = new DefaultBuildRuleBuilderParams(
        pathRelativizer, ruleKeyBuilderFactory);
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   * <p>
   * This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  ImmutableSet<IntermediateDexRule> createDepsForPreDexing(BuildRuleResolver ruleResolver) {
    ImmutableSet.Builder<IntermediateDexRule> preDexDeps = ImmutableSet.builder();
    ImmutableSet<JavaLibraryRule> transitiveJavaDeps = Classpaths
        .getClasspathEntries(originalDeps).keySet();
    for (JavaLibraryRule javaLibraryRule : transitiveJavaDeps) {
      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibraryRule.getPathToOutputFile() == null) {
        continue;
      }

      // If the rule is in the no_dx list, then do not pre-dex it.
      if (buildRulesToExcludeFromDex.contains(javaLibraryRule.getBuildTarget())) {
        continue;
      }

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibraryRule.getBuildTarget();
      BuildTarget preDexTarget = createBuildTargetWithDexFlavor(originalTarget);
      IntermediateDexRule preDexRule = (IntermediateDexRule) ruleResolver.get(preDexTarget);
      if (preDexRule != null) {
        preDexDeps.add(preDexRule);
        continue;
      }

      // Create a rule to get the list of the classes in the JavaLibraryRule.
      BuildTarget accumulateClassNamesBuildTarget = new BuildTarget(
          originalTarget.getBaseName(), originalTarget.getShortName(), "class_names");
      AccumulateClassNames.Builder accumulateClassNamesBuilder = AccumulateClassNames
          .newAccumulateClassNamesBuilder(buildRuleBuilderParams)
          .setBuildTarget(accumulateClassNamesBuildTarget)
          .setJavaLibraryToDex(javaLibraryRule)
          .addDep(originalTarget)
          .addVisibilityPattern(BuildTargetPattern.MATCH_ALL);
      BuildRule accumulateClassNamesRule = ruleResolver.buildAndAddToIndex(
          accumulateClassNamesBuilder);
      AccumulateClassNames accumulateClassNames =
          (AccumulateClassNames) accumulateClassNamesRule.getBuildable();

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      IntermediateDexRule.Builder preDexBuilder = IntermediateDexRule
          .newPreDexBuilder(buildRuleBuilderParams)
          .setBuildTarget(preDexTarget)
          .setAccumulateClassNamesDep(accumulateClassNames)
          .addDep(accumulateClassNamesBuildTarget)
          .addVisibilityPattern(BuildTargetPattern.MATCH_ALL);
      IntermediateDexRule preDex = ruleResolver.buildAndAddToIndex(preDexBuilder);
      preDexDeps.add(preDex);
    }
    return preDexDeps.build();
  }

  private static BuildTarget createBuildTargetWithDexFlavor(BuildTarget originalTarget) {
    return new BuildTarget(originalTarget.getBaseName(),
        originalTarget.getShortName(),
        "dex");
  }
}
