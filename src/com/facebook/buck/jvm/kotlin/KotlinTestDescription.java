/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
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
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.logging.Level;

public class KotlinTestDescription implements Description<KotlinTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("kotlin_test");

  private final KotlinBuckConfig kotlinBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions defaultJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public KotlinTestDescription(
      KotlinBuckConfig kotlinBuckConfig,
      JavaOptions javaOptions,
      JavacOptions defaultJavacOptions,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.javaOptions = javaOptions;
    this.defaultJavacOptions = defaultJavacOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
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
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    KotlincToJarStepFactory stepFactory = new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinCompiler().get(),
        args.extraKotlincArguments.get());

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps.get()))),
                        pathResolver.filterBuildRuleInputs(
                            defaultJavacOptions.getInputs(pathResolver))))
                    .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                pathResolver,
                args.srcs.get(),
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                defaultJavacOptions.getGeneratedSourceFolderName(),
                /* proguardConfig */ Optional.absent(),
                /* postprocessClassesCommands */ ImmutableList.of(),
                /* exportDeps */ ImmutableSortedSet.of(),
                /* providedDeps */ ImmutableSortedSet.of(),
                new BuildTargetSourcePath(abiJarTarget),
                /* trackClassUsage */ false,
                /* additionalClasspathEntries */ ImmutableSet.of(),
                stepFactory,
                /* resourcesRoot */ Optional.absent(),
                /* manifest file */ Optional.absent(),
                /* mavenCoords */ Optional.absent(),
                /* tests */ ImmutableSortedSet.of(),
                /* classesToRemoveFromJar */ ImmutableSet.of()
            ));

    JavaTest test =
        resolver.addToIndex(
            new JavaTest(
                params.copyWithDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
                pathResolver,
                testsLibrary,
                /* additionalClasspathEntries */ ImmutableSet.of(),
                args.labels.get(),
                args.contacts.get(),
                args.testType.or(TestType.JUNIT),
                javaOptions.getJavaRuntimeLauncher(),
                args.vmArgs.get(),
                /* nativeLibsEnvironment */ ImmutableMap.of(),
                args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
                args.env.get(),
                args.getRunTestSeparately(),
                args.getForkMode(),
                args.stdOutLogLevel,
                args.stdErrLogLevel));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(test.getBuildTarget())));

    return test;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends KotlinLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts = Optional.of(ImmutableSortedSet.of());
    public Optional<ImmutableSortedSet<Label>> labels = Optional.of(ImmutableSortedSet.of());
    public Optional<ImmutableList<String>> vmArgs = Optional.of(ImmutableList.of());
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<ImmutableMap<String, String>> env = Optional.of(ImmutableMap.of());

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }
    public ForkMode getForkMode() {
      return forkMode.or(ForkMode.NONE);
    }
  }
}
