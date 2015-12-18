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

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacArgInterpreter;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_library");

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

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
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    if (params.getBuildTarget().getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, pathResolver, args.srcs.get(), args.mavenCoords);
    }

    JavacOptions javacOptions = JavacArgInterpreter.populateJavacOptions(
        defaultOptions,
        params,
        resolver,
        pathResolver,
        args
    );

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps.get()))),
        javacOptions,
        DependencyMode.FIRST_ORDER,
        args.resourceUnionPackage);

    boolean hasDummyRDotJavaFlavor =
        params.getBuildTarget().getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ hasDummyRDotJavaFlavor);

    if (hasDummyRDotJavaFlavor) {
      return dummyRDotJava.get();
    } else {
      ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
      if (dummyRDotJava.isPresent()) {
        additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getRDotJavaBinFolder());
        ImmutableSortedSet<BuildRule> newDeclaredDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(params.getDeclaredDeps().get())
            .add(dummyRDotJava.get())
            .build();
        params = params.copyWithDeps(
            Suppliers.ofInstance(newDeclaredDeps),
            params.getExtraDeps());
      }

      BuildTarget abiJarTarget =
          BuildTarget.builder(params.getBuildTarget())
              .addFlavors(CalculateAbi.FLAVOR)
              .build();

      ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps.get());
      AndroidLibrary library =
          resolver.addToIndex(
              new AndroidLibrary(
                  params.appendExtraDeps(
                      Iterables.concat(
                          BuildRules.getExportedRules(
                              Iterables.concat(
                                  params.getDeclaredDeps().get(),
                                  exportedDeps,
                                  resolver.getAllRules(args.providedDeps.get()))),
                          pathResolver.filterBuildRuleInputs(
                              javacOptions.getInputs(pathResolver)))),
                  pathResolver,
                  args.srcs.get(),
                  ResourceValidator.validateResources(
                      pathResolver,
                      params.getProjectFilesystem(), args.resources.get()),
                  args.proguardConfig.transform(
                      SourcePaths.toSourcePath(params.getProjectFilesystem())),
                  args.postprocessClassesCommands.get(),
                  exportedDeps,
                  resolver.getAllRules(args.providedDeps.get()),
                  new BuildTargetSourcePath(abiJarTarget),
                  additionalClasspathEntries,
                  javacOptions,
                  args.resourcesRoot,
                  args.mavenCoords,
                  args.manifest,
                  args.tests.get()));

      resolver.addToIndex(
          CalculateAbi.of(
              abiJarTarget,
              pathResolver,
              params,
              new BuildTargetSourcePath(library.getBuildTarget())));

      return library;
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty() ||
        flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR)) ||
        flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<SourcePath> manifest;
    public Optional<String> resourceUnionPackage;
  }
}
