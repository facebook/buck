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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection.Builder;

/**
 * Responsible for creating the new java test runner rules, which is effectively a java_binary that
 * will have the test sources library be added as a dependency.
 */
public class JavaTestRunnerDescription
    implements DescriptionWithTargetGraph<JavaTestRunnerDescriptionArg>,
        VersionPropagator<JavaTestRunnerDescriptionArg>,
        ImplicitDepsInferringDescription<JavaTestRunnerDescriptionArg> {

  private final JavaBuckConfig javaBuckConfig;
  private final JavacFactory javacFactory;
  private final JavaConfiguredCompilerFactory defaultJavaCompilerFactory;

  public static final Flavor RUNNER_LIB_FLAVOR = InternalFlavor.of("runnerlib");

  public JavaTestRunnerDescription(
      ToolchainProvider toolchainProvider, JavaBuckConfig javaBuckConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.defaultJavaCompilerFactory =
        new JavaConfiguredCompilerFactory(this.javaBuckConfig, this.javacFactory);
  }

  @Override
  public Class<JavaTestRunnerDescriptionArg> getConstructorArgType() {
    return JavaTestRunnerDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaTestRunnerDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            context
                .getToolchainProvider()
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget.withFlavors(RUNNER_LIB_FLAVOR),
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                defaultJavaCompilerFactory,
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    DefaultJavaLibrary library = defaultJavaLibraryRules.buildLibrary();
    graphBuilder.addToIndex(library);

    return new JavaTestRunner(buildTarget, projectFilesystem, params, library, args.getMainClass());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      JavaTestRunnerDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractJavaTestRunnerDescriptionArg extends JavaLibraryDescription.CoreArg {
    String getMainClass();
  }
}
