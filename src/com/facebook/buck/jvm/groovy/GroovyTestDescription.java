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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
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
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.Optional;
import java.util.logging.Level;

public class GroovyTestDescription implements Description<GroovyTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_test");

  private final GroovyBuckConfig groovyBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions defaultJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public GroovyTestDescription(
      GroovyBuckConfig groovyBuckConfig,
      JavaOptions javaOptions,
      JavacOptions defaultJavacOptions,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.groovyBuckConfig = groovyBuckConfig;
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
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    GroovycToJarStepFactory stepFactory = new GroovycToJarStepFactory(
        groovyBuckConfig.getGroovyCompiler().get(),
        Optional.of(args.extraGroovycArguments),
        JavacOptionsFactory.create(
            defaultJavacOptions,
            params,
            resolver,
            pathResolver,
            args
        ));

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps))),
                        pathResolver.filterBuildRuleInputs(
                            defaultJavacOptions.getInputs(pathResolver))))
                    .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                pathResolver,
                args.srcs,
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources),
                defaultJavacOptions.getGeneratedSourceFolderName(),
                /* proguardConfig */ Optional.empty(),
                /* postprocessClassesCommands */ ImmutableList.of(),
                /* exportDeps */ ImmutableSortedSet.of(),
                /* providedDeps */ ImmutableSortedSet.of(),
                new BuildTargetSourcePath(abiJarTarget),
                /* trackClassUsage */ false,
                /* additionalClasspathEntries */ ImmutableSet.of(),
                stepFactory,
                /* resourcesRoot */ Optional.empty(),
                /* manifest file */ Optional.empty(),
                /* mavenCoords */ Optional.empty(),
                /* tests */ ImmutableSortedSet.of(),
                /* classesToRemoveFromJar */ ImmutableSet.of()
            ));

    JavaTest javaTest =
        resolver.addToIndex(
            new JavaTest(
                params.copyWithDeps(
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
                /* nativeLibsEnvironment */ ImmutableMap.of(),
                args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
                args.testCaseTimeoutMs,
                args.env,
                args.getRunTestSeparately(),
                args.getForkMode(),
                args.stdOutLogLevel,
                args.stdErrLogLevel));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(testsLibrary.getBuildTarget())));

    return javaTest;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends GroovyLibraryDescription.Arg {
    public ImmutableSortedSet<String> contacts = ImmutableSortedSet.of();
    public ImmutableSortedSet<Label> labels = ImmutableSortedSet.of();
    public ImmutableList<String> vmArgs = ImmutableList.of();
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Long> testCaseTimeoutMs;
    public ImmutableMap<String, String> env = ImmutableMap.of();

    public boolean getRunTestSeparately() {
      return runTestSeparately.orElse(false);
    }

    public ForkMode getForkMode() {
      return forkMode.orElse(ForkMode.NONE);
    }
  }
}
