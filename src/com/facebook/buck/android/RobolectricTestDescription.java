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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.jvm.java.CalculateAbiFromClasses;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class RobolectricTestDescription implements Description<RobolectricTestDescription.Arg> {


  private final JavaBuckConfig javaBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions templateOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public RobolectricTestDescription(
      JavaBuckConfig javaBuckConfig,
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaOptions = javaOptions;
    this.templateOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
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
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateOptions,
            params,
            resolver,
            args);

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyReplacingExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps))),
        JavacFactory.create(ruleFinder, javaBuckConfig, args),
        javacOptions,
        DependencyMode.TRANSITIVE,
        /* forceFinalResourceIds */ true,
        /* resourceUnionPackage */ Optional.empty(),
        /* rName */ Optional.empty(),
        args.useOldStyleableFormat);

    if (HasJavaAbi.isClassAbiTarget(params.getBuildTarget())) {
      if (params.getBuildTarget().getFlavors().contains(
          AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR)) {
        return graphEnhancer.getBuildableForAndroidResourcesAbi(resolver, ruleFinder);
      }
      BuildTarget testTarget = HasJavaAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule testRule = resolver.requireRule(testTarget);
      return CalculateAbiFromClasses.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(testRule.getSourcePathToOutput()));
    }

    ImmutableList<String> vmArgs = args.vmArgs;

    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ true);

    if (dummyRDotJava.isPresent()) {
      ImmutableSortedSet<BuildRule> newDeclaredDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getDeclaredDeps().get())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyReplacingDeclaredAndExtraDeps(
          Suppliers.ofInstance(newDeclaredDeps),
          params.getExtraDeps());
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            params,
            args.useCxxLibraries,
            args.cxxLibraryWhitelist,
            resolver,
            ruleFinder,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildRuleParams testsLibraryParams = params
        .withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            DefaultJavaLibrary
                .builder(testsLibraryParams, resolver, javaBuckConfig)
                .setArgs(args)
                .setJavacOptions(javacOptions)
                .setJavacOptionsAmender(new BootClasspathAppender())
                .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
                .setTrackClassUsage(javacOptions.trackClassUsage())
                .build());


    return new RobolectricTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        ruleFinder,
        testsLibrary,
        args.labels,
        args.contacts,
        TestType.JUNIT,
        javaOptions,
        vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        dummyRDotJava,
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.testCaseTimeoutMs,
        args.env,
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.stdOutLogLevel,
        args.stdErrLogLevel,
        args.robolectricRuntimeDependency,
        args.robolectricManifest);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaTestDescription.Arg {
    public Optional<String> robolectricRuntimeDependency;
    public Optional<SourcePath> robolectricManifest;
    public boolean useOldStyleableFormat = false;
  }
}
