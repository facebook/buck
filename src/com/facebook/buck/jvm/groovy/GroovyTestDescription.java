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

import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

public class GroovyTestDescription implements Description<GroovyTestDescriptionArg> {

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
  public Class<GroovyTestDescriptionArg> getConstructorArgType() {
    return GroovyTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GroovyTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    JavacOptions javacOptions =
        JavacOptionsFactory.create(defaultJavacOptions, params, resolver, args);

    BuildRuleParams testsLibraryParams =
        params.withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
    DefaultJavaLibraryBuilder defaultJavaLibraryBuilder =
        new DefaultGroovyLibraryBuilder(
                targetGraph,
                testsLibraryParams,
                resolver,
                cellRoots,
                javacOptions,
                groovyBuckConfig)
            .setArgs(args)
            .setGeneratedSourceFolder(defaultJavacOptions.getGeneratedSourceFolderName());

    if (HasJavaAbi.isAbiTarget(params.getBuildTarget())) {
      return defaultJavaLibraryBuilder.buildAbi();
    }

    JavaLibrary testsLibrary = resolver.addToIndex(defaultJavaLibraryBuilder.build());

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new JavaTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.getVmArgs(),
        /* nativeLibsEnvironment */ ImmutableMap.of(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getTestCaseTimeoutMs(),
        args.getEnv(),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGroovyTestDescriptionArg extends GroovyLibraryDescription.CoreArg {
    @Value.NaturalOrder
    ImmutableSortedSet<String> getContacts();

    ImmutableList<String> getVmArgs();

    Optional<TestType> getTestType();

    Optional<Level> getStdErrLogLevel();

    Optional<Level> getStdOutLogLevel();

    Optional<Long> getTestRuleTimeoutMs();

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, String> getEnv();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default ForkMode getForkMode() {
      return ForkMode.NONE;
    }
  }
}
