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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidLibraryGraphEnhancer.ResourceDependencyMode;
import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.ImmutableJavacOptions;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaSourceJar;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("android_library");

  private final JavacOptions defaultOptions;

  public AndroidLibraryDescription(JavacOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    if (params.getBuildTarget().getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, pathResolver, args.srcs.get());
    }

    ImmutableJavacOptions.Builder javacOptions = JavaLibraryDescription.getJavacOptions(
        pathResolver,
        args,
        defaultOptions);

    AnnotationProcessingParams annotationParams = args.buildAnnotationProcessingParams(
        params.getBuildTarget(),
        params.getProjectFilesystem(),
        resolver);
    javacOptions.setAnnotationProcessingParams(annotationParams);

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps.get()))),
        javacOptions.build(),
        ResourceDependencyMode.FIRST_ORDER);
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.createBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ false);

    ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
    if (dummyRDotJava.isPresent()) {
      additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getRDotJavaBinFolder());
      ImmutableSortedSet<BuildRule> newDeclaredDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getDeclaredDeps())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyWithDeps(
          Suppliers.ofInstance(newDeclaredDeps),
          Suppliers.ofInstance(params.getExtraDeps()));
    }

    return new AndroidLibrary(
        params,
        pathResolver,
        args.srcs.get(),
        JavaLibraryDescription.validateResources(
            pathResolver,
            args,
            params.getProjectFilesystem()),
        args.proguardConfig,
        args.postprocessClassesCommands.get(),
        resolver.getAllRules(args.exportedDeps.get()),
        resolver.getAllRules(args.providedDeps.get()),
        additionalClasspathEntries,
        javacOptions.build(),
        args.resourcesRoot,
        args.manifest,
        /* isPrebuiltAar */ false);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.contains(JavaLibrary.SRC_JAR) || flavors.isEmpty();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<SourcePath> manifest;
  }
}
