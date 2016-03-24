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
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.logging.Level;

public class ScalaTestDescription implements Description<ScalaTestDescription.Arg>,
    ImplicitDepsInferringDescription<ScalaTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("scala_test");

  private final ScalaBuckConfig config;
  private final JavaOptions javaOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;
  private final Optional<Path> testTempDirOverride;

  public ScalaTestDescription(
      ScalaBuckConfig config,
      JavaOptions javaOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform,
      Optional<Path> testTempDirOverride) {
    this.config = config;
    this.javaOptions = javaOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
    this.testTempDirOverride = testTempDirOverride;
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
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    final BuildRule scalaLibrary = resolver.getRule(config.getScalaLibraryTarget());
    BuildRuleParams params = rawParams.copyWithDeps(
        new Supplier<ImmutableSortedSet<BuildRule>>() {
          @Override
          public ImmutableSortedSet<BuildRule> get() {
            return ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(rawParams.getDeclaredDeps().get())
                .add(scalaLibrary)
                .build();
          }
        },
        rawParams.getExtraDeps()
    );

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            params,
            args.useCxxLibraries,
            resolver,
            pathResolver,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    Tool scalac = config.getScalac(resolver);

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavor(CalculateAbi.FLAVOR);

    JavaTest test =
        resolver.addToIndex(
            new JavaTest(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps.get()))),
                        scalac.getDeps(pathResolver))),
                pathResolver,
                args.srcs.get(),
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                /* generatedSourceFolderName */ Optional.<Path>absent(),
                args.labels.get(),
                args.contacts.get(),
                /* proguardConfig */ Optional.<SourcePath>absent(),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                args.testType.or(TestType.JUNIT),
                new ScalacToJarStepFactory(
                    scalac,
                    ImmutableList.<String>builder()
                        .addAll(config.getCompilerFlags())
                        .addAll(args.extraArguments.get())
                        .build()
                ),
                javaOptions.getJavaRuntimeLauncher(),
                args.vmArgs.get(),
                /* sourcesUnderTest */ cxxLibraryEnhancement.nativeLibsEnvironment,
                ImmutableSet.<BuildRule>of(),
                args.resourcesRoot,
                args.mavenCoords,
                args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
                args.runTestSeparately.or(false),
                args.stdOutLogLevel,
                args.stdErrLogLevel,
                testTempDirOverride));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(test.getBuildTarget())));

    return test;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    return ImmutableList.<BuildTarget>builder()
        .add(config.getScalaLibraryTarget())
        .addAll(config.getScalacTarget().asSet())
        .build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends ScalaLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Boolean> useCxxLibraries;
    public Optional<Long> testRuleTimeoutMs;
  }
}
