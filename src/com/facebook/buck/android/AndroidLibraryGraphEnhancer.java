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

import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.DependencyMode;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class AndroidLibraryGraphEnhancer {

  public static final Flavor DUMMY_R_DOT_JAVA_FLAVOR = InternalFlavor.of("dummy_r_dot_java");

  private final BuildTarget dummyRDotJavaBuildTarget;
  private final BuildRuleParams originalBuildRuleParams;
  private final Javac javac;
  private final JavacOptions javacOptions;
  private final DependencyMode resourceDependencyMode;
  private final boolean forceFinalResourceIds;
  private final Optional<String> resourceUnionPackage;
  private final Optional<String> finalRName;
  private final boolean useOldStyleableFormat;

  public AndroidLibraryGraphEnhancer(
      BuildTarget buildTarget,
      BuildRuleParams buildRuleParams,
      Javac javac,
      JavacOptions javacOptions,
      DependencyMode resourceDependencyMode,
      boolean forceFinalResourceIds,
      Optional<String> resourceUnionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat) {
    Preconditions.checkState(!HasJavaAbi.isAbiTarget(buildTarget));
    this.dummyRDotJavaBuildTarget = getDummyRDotJavaTarget(buildTarget);
    this.originalBuildRuleParams = buildRuleParams;
    this.javac = javac;
    // Override javacoptions because DummyRDotJava doesn't require annotation processing.
    this.javacOptions =
        JavacOptions.builder(javacOptions)
            .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
            .build();
    this.resourceDependencyMode = resourceDependencyMode;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.resourceUnionPackage = resourceUnionPackage;
    this.finalRName = finalRName;
    this.useOldStyleableFormat = useOldStyleableFormat;
  }

  public static BuildTarget getDummyRDotJavaTarget(BuildTarget buildTarget) {
    return BuildTarget.builder(buildTarget).addFlavors(DUMMY_R_DOT_JAVA_FLAVOR).build();
  }

  public Optional<DummyRDotJava> getBuildableForAndroidResources(
      BuildRuleResolver ruleResolver, boolean createBuildableIfEmptyDeps) {
    Optional<BuildRule> previouslyCreated = ruleResolver.getRuleOptional(dummyRDotJavaBuildTarget);
    if (previouslyCreated.isPresent()) {
      return previouslyCreated.map(input -> (DummyRDotJava) input);
    }
    ImmutableSortedSet<BuildRule> originalDeps = originalBuildRuleParams.getBuildDeps();
    ImmutableSet<HasAndroidResourceDeps> androidResourceDeps;

    switch (resourceDependencyMode) {
      case FIRST_ORDER:
        androidResourceDeps =
            FluentIterable.from(originalDeps)
                .filter(HasAndroidResourceDeps.class)
                .filter(input -> input.getRes() != null)
                .toSet();
        break;
      case TRANSITIVE:
        androidResourceDeps =
            UnsortedAndroidResourceDeps.createFrom(originalDeps, Optional.empty())
                .getResourceDeps();
        break;
      default:
        throw new IllegalStateException(
            "Invalid resource dependency mode: " + resourceDependencyMode);
    }

    if (androidResourceDeps.isEmpty() && !createBuildableIfEmptyDeps) {
      return Optional.empty();
    }

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);

    CompileToJarStepFactory compileToJarStepFactory =
        new JavacToJarStepFactory(javac, javacOptions, JavacOptionsAmender.IDENTITY);
    BuildRuleParams dummyRDotJavaParams =
        compileToJarStepFactory
            .addInputs(
                // DummyRDotJava inherits no dependencies from its android_library beyond the compiler
                // that is used to build it
                originalBuildRuleParams.copyReplacingDeclaredAndExtraDeps(
                    ImmutableSortedSet::of, ImmutableSortedSet::of),
                ruleFinder)
            .withBuildTarget(dummyRDotJavaBuildTarget);

    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            dummyRDotJavaParams,
            ruleFinder,
            androidResourceDeps,
            compileToJarStepFactory,
            forceFinalResourceIds,
            resourceUnionPackage,
            finalRName,
            useOldStyleableFormat);
    ruleResolver.addToIndex(dummyRDotJava);

    return Optional.of(dummyRDotJava);
  }
}
