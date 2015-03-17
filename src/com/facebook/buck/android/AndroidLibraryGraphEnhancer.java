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

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidLibraryGraphEnhancer {

  public static enum ResourceDependencyMode {
    FIRST_ORDER,
    TRANSITIVE,
  }

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR = ImmutableFlavor.of("dummy_r_dot_java");

  private final BuildTarget dummyRDotJavaBuildTarget;
  private final BuildRuleParams originalBuildRuleParams;
  private final JavacOptions javacOptions;
  private final ResourceDependencyMode resourceDependencyMode;

  public AndroidLibraryGraphEnhancer(
      BuildTarget buildTarget,
      BuildRuleParams buildRuleParams,
      JavacOptions javacOptions,
      ResourceDependencyMode resourceDependencyMode) {
    this.dummyRDotJavaBuildTarget = getDummyRDotJavaTarget(buildTarget);
    this.originalBuildRuleParams = buildRuleParams;
    // Override javacoptions because DummyRDotJava doesn't require annotation processing.
    this.javacOptions = JavacOptions.builder(javacOptions)
        .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
        .build();
    this.resourceDependencyMode = resourceDependencyMode;
  }

  public static ImmutableBuildTarget getDummyRDotJavaTarget(BuildTarget buildTarget) {
    return BuildTarget.builder(buildTarget)
        .addFlavors(DUMMY_R_DOT_JAVA_FLAVOR)
        .build();
  }

  public Optional<DummyRDotJava> createBuildableForAndroidResources(
      BuildRuleResolver ruleResolver,
      boolean createBuildableIfEmptyDeps) {
    ImmutableSortedSet<BuildRule> originalDeps = originalBuildRuleParams.getDeps();
    ImmutableSet<HasAndroidResourceDeps> androidResourceDeps;

    switch (resourceDependencyMode) {
      case FIRST_ORDER:
        androidResourceDeps = FluentIterable.from(originalDeps)
            .filter(HasAndroidResourceDeps.class)
            .filter(HasAndroidResourceDeps.NON_EMPTY_RESOURCE)
            .toSet();
        break;
      case TRANSITIVE:
        androidResourceDeps = UnsortedAndroidResourceDeps.createFrom(
            originalDeps,
            Optional.<UnsortedAndroidResourceDeps.Callback>absent())
            .getResourceDeps();
        break;
      default:
        throw new IllegalStateException(
            "Invalid resource dependency mode: " + resourceDependencyMode);
    }

    if (androidResourceDeps.isEmpty() && !createBuildableIfEmptyDeps) {
      return Optional.absent();
    }

    // The androidResourceDeps may contain Buildables, but we need the actual BuildRules. Since this
    // is going to be used to modify the build graph, we can't just wrap the buildables. Fortunately
    // we know that the buildables come from the originalDeps.
    ImmutableSortedSet.Builder<BuildRule> actualDeps = ImmutableSortedSet.naturalOrder();
    for (HasAndroidResourceDeps dep : androidResourceDeps) {
      // If this ever returns null, something has gone horrifically awry.
      actualDeps.add(ruleResolver.getRule(dep.getBuildTarget()));
    }

    BuildRuleParams dummyRDotJavaParams = originalBuildRuleParams.copyWithChanges(
        BuildRuleType.DUMMY_R_DOT_JAVA,
        dummyRDotJavaBuildTarget,
        Suppliers.ofInstance(actualDeps.build()),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        dummyRDotJavaParams,
        new SourcePathResolver(ruleResolver),
        androidResourceDeps,
        javacOptions);
    ruleResolver.addToIndex(dummyRDotJava);
    return Optional.of(dummyRDotJava);
  }

}
