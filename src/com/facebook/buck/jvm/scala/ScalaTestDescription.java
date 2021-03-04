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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.cell.CellPathResolver;
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
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
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
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.function.Function;

public class ScalaTestDescription
    implements DescriptionWithTargetGraph<ScalaTestDescriptionArg>,
        ImplicitDepsInferringDescription<ScalaTestDescription.AbstractScalaTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final ScalaBuckConfig config;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final Function<TargetConfiguration, JavaOptions> java11OptionsForTests;
  private final JavacFactory javacFactory;

  public ScalaTestDescription(
      ToolchainProvider toolchainProvider,
      ScalaBuckConfig config,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.config = config;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.java11OptionsForTests =
        JavaOptionsProvider.getDefaultJava11OptionsForTests(toolchainProvider);
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<ScalaTestDescriptionArg> getConstructorArgType() {
    return ScalaTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams rawParams,
      ScalaTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            rawParams,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            graphBuilder,
            getCxxPlatform(buildTarget.getTargetConfiguration())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            javaBuckConfig.shouldAddBuckLDSymlinkTree());
    BuildRuleParams params = cxxLibraryEnhancement.updatedParams;
    BuildTarget javaLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            projectFilesystem.getRootPath(),
            args);

    CellPathResolver cellRoots = context.getCellPathResolver();
    DefaultJavaLibraryRules scalaLibraryBuilder =
        ScalaLibraryBuilder.newInstance(
                javaLibraryBuildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                config,
                javaBuckConfig,
                javaCDBuckConfig,
                downwardApiConfig,
                args,
                javacFactory,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return scalaLibraryBuilder.buildAbi();
    }

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            JavaTestDescription.MACRO_EXPANDERS);
    JavaLibrary testsLibrary = graphBuilder.addToIndex(scalaLibraryBuilder.buildLibrary());

    Function<TargetConfiguration, JavaOptions> javaRuntimeConfig =
        javacOptions.getLanguageLevelOptions().getTargetLevel().equals("11")
            ? java11OptionsForTests
            : javaOptionsForTests;
    JavaOptions javaOptions = javaRuntimeConfig.apply(buildTarget.getTargetConfiguration());

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        Optional.empty(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().isPresent() ? args.getTestType().get() : TestType.JUNIT,
        javacOptions.getLanguageLevelOptions().getTargetLevel(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        Lists.transform(args.getVmArgs(), macrosConverter::convert),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        cxxLibraryEnhancement.requiredPaths,
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

  private UnresolvedCxxPlatform getCxxPlatform(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class)
        .getDefaultUnresolvedCxxPlatform();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractScalaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.add(config.getScalaLibraryTarget(buildTarget.getTargetConfiguration()));
    Optionals.addIfPresent(
        config.getScalacTarget(buildTarget.getTargetConfiguration()), extraDepsBuilder);
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractScalaTestDescriptionArg
      extends ScalaLibraryDescription.CoreArg, JavaTestDescription.CoreArg {}
}
