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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class JavaLibraryGraphEnhancer {

  private static final String DUMMY_R_DOT_JAVA_FLAVOR = "dummy_r_dot_java";

  private final BuildTarget dummyRDotJavaBuildTarget;
  private final BuildRuleParams originalBuildRuleParams;
  private final AbstractBuildRuleBuilderParams buildRuleBuilderParams;

  public JavaLibraryGraphEnhancer(
      BuildTarget buildTarget,
      BuildRuleParams buildRuleParams,
      AbstractBuildRuleBuilderParams buildRuleBuilderParams) {
    Preconditions.checkNotNull(buildTarget);
    this.dummyRDotJavaBuildTarget = new BuildTarget(
        buildTarget.getBaseName(),
        buildTarget.getShortName(),
        DUMMY_R_DOT_JAVA_FLAVOR);
    this.originalBuildRuleParams = Preconditions.checkNotNull(buildRuleParams);
    this.buildRuleBuilderParams = Preconditions.checkNotNull(buildRuleBuilderParams);
  }

  public Result createBuildableForAndroidResources(
      BuildRuleResolver ruleResolver,
      boolean createBuildableIfEmptyDeps) {
    ImmutableSortedSet<BuildRule> originalDeps = originalBuildRuleParams.getDeps();
    ImmutableList<HasAndroidResourceDeps> androidResourceDeps =
        UberRDotJavaUtil.getAndroidResourceDeps(originalDeps);

    if (androidResourceDeps.isEmpty() && !createBuildableIfEmptyDeps) {
      return new Result(originalBuildRuleParams, Optional.<DummyRDotJava>absent());
    }

    DummyRDotJava.Builder ruleBuilder =
        DummyRDotJava.newDummyRDotJavaBuildableBuilder(buildRuleBuilderParams)
            .setBuildTarget(dummyRDotJavaBuildTarget)
            .setAndroidResourceDeps(androidResourceDeps);
    for (HasAndroidResourceDeps resourceDep : androidResourceDeps) {
      ruleBuilder.addDep(resourceDep.getBuildTarget());
    }

    BuildRule dummyRDotJavaBuildRule = ruleResolver.buildAndAddToIndex(ruleBuilder);
    final DummyRDotJava dummyRDotJava = (DummyRDotJava) dummyRDotJavaBuildRule.getBuildable();

    ImmutableSortedSet<BuildRule> totalDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(originalDeps)
        .add(dummyRDotJavaBuildRule)
        .build();

    BuildRuleParams newBuildRuleParams = new BuildRuleParams(
        originalBuildRuleParams.getBuildTarget(),
        totalDeps,
        originalBuildRuleParams.getVisibilityPatterns(),
        originalBuildRuleParams.getPathRelativizer(),
        originalBuildRuleParams.getRuleKeyBuilderFactory());

    return new Result(newBuildRuleParams, Optional.of(dummyRDotJava));
  }

  public static class Result {
    private final BuildRuleParams buildRuleParams;
    private final Optional<DummyRDotJava> dummyRDotJava;

    private Result(BuildRuleParams buildRuleParams, Optional<DummyRDotJava> dummyRDotJava) {
      this.buildRuleParams = Preconditions.checkNotNull(buildRuleParams);
      this.dummyRDotJava = Preconditions.checkNotNull(dummyRDotJava);
    }

    public BuildRuleParams getBuildRuleParams() {
      return buildRuleParams;
    }

    public Optional<DummyRDotJava> getOptionalDummyRDotJava() {
      return dummyRDotJava;
    }

  }
}
