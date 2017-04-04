/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;
import java.util.logging.Level;

public class ScalaTestDescription implements Description<ScalaTestDescription.Arg>,
    ImplicitDepsInferringDescription<ScalaTestDescription.Arg> {

  private final ScalaBuckConfig config;
  private final JavaOptions javaOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public ScalaTestDescription(
      ScalaBuckConfig config,
      JavaOptions javaOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.config = config;
    this.javaOptions = javaOptions;
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
      final BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            rawParams,
            args.useCxxLibraries,
            args.cxxLibraryWhitelist,
            resolver,
            ruleFinder,
            cxxPlatform);
    BuildRuleParams params = cxxLibraryEnhancement.updatedParams;
    BuildRuleParams javaLibraryParams =
        params.withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    ScalaLibraryBuilder scalaLibraryBuilder = new ScalaLibraryBuilder(
        javaLibraryParams,
        resolver,
        config)
        .setArgs(args);

    if (HasJavaAbi.isAbiTarget(rawParams.getBuildTarget())) {
      return scalaLibraryBuilder.buildAbi();
    }

    JavaLibrary testsLibrary = resolver.addToIndex(scalaLibraryBuilder.build());

    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new JavaTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.labels,
        args.contacts,
        args.testType.orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.testCaseTimeoutMs,
        args.env,
        args.runTestSeparately.orElse(false),
        args.forkMode.orElse(ForkMode.NONE),
        args.stdOutLogLevel,
        args.stdErrLogLevel);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder
        .add(config.getScalaLibraryTarget())
        .addAll(OptionalCompat.asSet(config.getScalacTarget()));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends ScalaLibraryDescription.Arg {
    public ImmutableSortedSet<String> contacts = ImmutableSortedSet.of();
    public ImmutableList<String> vmArgs = ImmutableList.of();
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Boolean> useCxxLibraries;
    public ImmutableSet<BuildTarget> cxxLibraryWhitelist = ImmutableSet.of();
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Long> testCaseTimeoutMs;
    public ImmutableMap<String, String> env = ImmutableMap.of();
  }
}
