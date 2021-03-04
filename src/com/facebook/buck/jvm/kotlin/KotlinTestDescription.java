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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.function.Function;

/** Description for kotlin_test. */
public class KotlinTestDescription
    implements DescriptionWithTargetGraph<KotlinTestDescriptionArg>,
        ImplicitDepsInferringDescription<KotlinTestDescriptionArg> {

  private final KotlinBuckConfig kotlinBuckConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final Function<TargetConfiguration, JavaOptions> java11OptionsForTests;
  private final JavacFactory javacFactory;
  private final LoadingCache<TargetConfiguration, JavacOptions> defaultJavacOptions;

  public KotlinTestDescription(
      ToolchainProvider toolchainProvider,
      KotlinBuckConfig kotlinBuckConfig,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.java11OptionsForTests =
        JavaOptionsProvider.getDefaultJava11OptionsForTests(toolchainProvider);
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.defaultJavacOptions =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<TargetConfiguration, JavacOptions>() {
                  @Override
                  public JavacOptions load(TargetConfiguration key) {
                    return toolchainProvider
                        .getByName(
                            JavacOptionsProvider.DEFAULT_NAME, key, JavacOptionsProvider.class)
                        .getJavacOptions();
                  }
                });
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<KotlinTestDescriptionArg> getConstructorArgType() {
    return KotlinTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      KotlinTestDescriptionArg args) {
    BuildTarget testsLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            defaultJavacOptions.getUnchecked(buildTarget.getTargetConfiguration()),
            buildTarget,
            graphBuilder,
            projectFilesystem.getRootPath(),
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        KotlinLibraryBuilder.newInstance(
                testsLibraryBuildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                kotlinBuckConfig,
                javaBuckConfig,
                javaCDBuckConfig,
                downwardApiConfig,
                args,
                javacFactory,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    DefaultJavaLibrary testsLibrary =
        graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());

    Function<TargetConfiguration, JavaOptions> javaRuntimeConfig =
        javacOptions.getLanguageLevelOptions().getTargetLevel().equals("11")
            ? java11OptionsForTests
            : javaOptionsForTests;
    JavaOptions javaOptions = javaRuntimeConfig.apply(buildTarget.getTargetConfiguration());

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            graphBuilder,
            JavaTestDescription.MACRO_EXPANDERS);
    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        Optional.of(
            resolver ->
                ImmutableList.of(
                    resolver.getAbsolutePath(kotlinBuckConfig.getPathToStdlibJar()).getPath())),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javacOptions.getLanguageLevelOptions().getTargetLevel(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        Lists.transform(args.getVmArgs(), macrosConverter::convert),
        ImmutableMap.of(), /* nativeLibsEnvironment */
        ImmutableSet.of(), /* nativeLibsRequiredPaths */
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                javaBuckConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getResources(),
        javaBuckConfig
            .getDelegate()
            .getView(TestBuckConfig.class)
            .useRelativePathsInClasspathFile(),
        downwardApiConfig.isEnabledForTests());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      KotlinTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractKotlinTestDescriptionArg
      extends KotlinLibraryDescription.CoreArg, JavaTestDescription.CoreArg {}
}
