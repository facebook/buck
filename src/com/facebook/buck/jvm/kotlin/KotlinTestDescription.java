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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;
import org.immutables.value.Value;

public class KotlinTestDescription implements DescriptionWithTargetGraph<KotlinTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final KotlinBuckConfig kotlinBuckConfig;
  private final JavaBuckConfig javaBuckConfig;

  public KotlinTestDescription(
      ToolchainProvider toolchainProvider,
      KotlinBuckConfig kotlinBuckConfig,
      JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
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
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            projectFilesystem,
            graphBuilder,
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        KotlinLibraryBuilder.newInstance(
                testsLibraryBuildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                context.getCellPathResolver(),
                kotlinBuckConfig,
                javaBuckConfig,
                args,
                JavacFactory.getDefault(toolchainProvider))
            .setJavacOptions(javacOptions)
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    DefaultJavaLibrary testsLibrary =
        graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(context.getCellPathResolver())
            .setExpanders(JavaTestDescription.MACRO_EXPANDERS)
            .build();
    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        ImmutableSet.of(Either.ofRight(kotlinBuckConfig.getPathToStdlibJar())),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptionsForTests()
            .getJavaRuntimeLauncher(),
        args.getVmArgs(),
        ImmutableMap.of(), /* nativeLibsEnvironment */
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(javaBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(
            Maps.transformValues(args.getEnv(), x -> macrosConverter.convert(x, graphBuilder))),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getUnbundledResourcesRoot());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractKotlinTestDescriptionArg
      extends KotlinLibraryDescription.CoreArg, JavaTestDescription.CoreArg {}
}
