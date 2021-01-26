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

package com.facebook.buck.android;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.SortedSet;

public class AndroidLibraryGraphEnhancer {

  public static final Flavor DUMMY_R_DOT_JAVA_FLAVOR = InternalFlavor.of("dummy_r_dot_java");

  private final BuildTarget dummyRDotJavaBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final Javac javac;
  private final JavacOptions javacOptions;
  private final Optional<String> resourceUnionPackage;
  private final Optional<String> finalRName;
  private final boolean useOldStyleableFormat;
  private final ProjectFilesystem projectFilesystem;
  private final boolean skipNonUnionRDotJava;
  private final boolean withDownwardApi;

  public AndroidLibraryGraphEnhancer(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SortedSet<BuildRule> buildRuleDeps,
      Javac javac,
      JavacOptions javacOptions,
      Optional<String> resourceUnionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      boolean skipNonUnionRDotJava,
      boolean withDownwardApi) {
    this.projectFilesystem = projectFilesystem;
    Preconditions.checkState(!JavaAbis.isAbiTarget(buildTarget));
    this.dummyRDotJavaBuildTarget = getDummyRDotJavaTarget(buildTarget);
    this.originalDeps = ImmutableSortedSet.copyOf(buildRuleDeps);
    this.javac = javac;
    // Override javacoptions because DummyRDotJava doesn't require annotation processing.
    this.javacOptions =
        JavacOptions.builder(javacOptions)
            .setJavaAnnotationProcessorParams(JavacPluginParams.EMPTY)
            .build();
    this.resourceUnionPackage = resourceUnionPackage;
    this.finalRName = finalRName;
    this.useOldStyleableFormat = useOldStyleableFormat;
    this.skipNonUnionRDotJava = skipNonUnionRDotJava;
    this.withDownwardApi = withDownwardApi;
  }

  public static BuildTarget getDummyRDotJavaTarget(BuildTarget buildTarget) {
    return buildTarget.withAppendedFlavors(DUMMY_R_DOT_JAVA_FLAVOR);
  }

  public Optional<DummyRDotJava> getBuildableForAndroidResources(
      ActionGraphBuilder graphBuilder, boolean createBuildableIfEmptyDeps) {
    // Check if it exists first, since deciding whether to actually create it requires some
    // computation.
    Optional<BuildRule> previouslyCreated = graphBuilder.getRuleOptional(dummyRDotJavaBuildTarget);
    if (previouslyCreated.isPresent()) {
      return previouslyCreated.map(input -> (DummyRDotJava) input);
    }

    ImmutableSet<HasAndroidResourceDeps> androidResourceDeps =
        RichStream.from(originalDeps)
            .filter(HasAndroidResourceDeps.class)
            .filter(input -> input.getRes() != null)
            .toImmutableSet();

    if (androidResourceDeps.isEmpty() && !createBuildableIfEmptyDeps) {
      return Optional.empty();
    }

    BuildRule dummyRDotJava =
        graphBuilder.computeIfAbsent(
            dummyRDotJavaBuildTarget,
            ignored -> {
              JavacOptions filteredOptions = javacOptions.withExtraArguments(ImmutableList.of());

              JavacToJarStepFactory compileToJarStepFactory =
                  new JavacToJarStepFactory(
                      filteredOptions, ExtraClasspathProvider.EMPTY, withDownwardApi);

              return new DummyRDotJava(
                  dummyRDotJavaBuildTarget,
                  projectFilesystem,
                  graphBuilder,
                  androidResourceDeps,
                  compileToJarStepFactory,
                  javac,
                  resourceUnionPackage,
                  finalRName,
                  useOldStyleableFormat,
                  skipNonUnionRDotJava);
            });

    return Optional.of((DummyRDotJava) dummyRDotJava);
  }
}
