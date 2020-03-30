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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.CoreArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

public class KotlinConfiguredCompilerFactory extends ConfiguredCompilerFactory {

  private final KotlinBuckConfig kotlinBuckConfig;
  private final BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
      extraClasspathProviderSupplier;
  private final JavacFactory javacFactory;

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig, JavacFactory javacFactory) {
    this(
        kotlinBuckConfig,
        (toolchainProvider, toolchainTargetConfiguration) -> ExtraClasspathProvider.EMPTY,
        javacFactory);
  }

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig,
      BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
          extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    super();
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.extraClasspathProviderSupplier = extraClasspathProviderSupplier;
    this.javacFactory = javacFactory;
  }

  @Override
  public CompileToJarStepFactory configure(
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {
    CoreArg kotlinArgs = Objects.requireNonNull((CoreArg) args);
    Path pathToAbiGenerationPluginJar =
        shouldGenerateSourceAbi() ? kotlinBuckConfig.getPathToAbiGenerationPluginJar() : null;
    return new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinc(),
        kotlinBuckConfig.getKotlinHomeLibraries(),
        pathToAbiGenerationPluginJar,
        kotlinArgs.getExtraKotlincArguments(),
        kotlinArgs.getKotlincPlugins(),
        getFriendSourcePaths(buildRuleResolver, kotlinArgs.getFriendPaths(), kotlinBuckConfig),
        kotlinArgs.getAnnotationProcessingTool().orElse(AnnotationProcessingTool.KAPT),
        kotlinArgs.getKaptApOptions(),
        extraClasspathProviderSupplier.apply(toolchainProvider, targetConfiguration),
        getJavac(buildRuleResolver, args, targetConfiguration),
        javacOptions);
  }

  @Override
  public Optional<ExtraClasspathProvider> getExtraClasspathProvider(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    return Optional.ofNullable(
        extraClasspathProviderSupplier.apply(toolchainProvider, toolchainTargetConfiguration));
  }

  private Javac getJavac(
      BuildRuleResolver resolver,
      @Nullable JvmLibraryArg arg,
      TargetConfiguration toolchainTargetConfiguration) {
    return javacFactory.create(resolver, arg, toolchainTargetConfiguration);
  }

  @Override
  public boolean shouldDesugarInterfaceMethods() {
    // Enable desugaring for kotlin libraries by default
    return true;
  }

  @Override
  public boolean shouldCompileAgainstAbis() {
    return kotlinBuckConfig.shouldCompileAgainstAbis();
  }

  @Override
  public AbiGenerationMode getAbiGenerationMode() {
    return kotlinBuckConfig.getAbiGenerationMode();
  }

  @Override
  public boolean shouldGenerateSourceAbi() {
    return kotlinBuckConfig.getAbiGenerationMode().isSourceAbi();
  }

  @Override
  public boolean sourceAbiCopiesFromLibraryTargetOutput() {
    return true;
  }

  private static ImmutableList<SourcePath> getFriendSourcePaths(
      BuildRuleResolver buildRuleResolver,
      ImmutableSortedSet<BuildTarget> friendPaths,
      KotlinBuckConfig kotlinBuckConfig) {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    boolean shouldCompileAgainstAbis = kotlinBuckConfig.shouldCompileAgainstAbis();

    for (BuildRule rule : buildRuleResolver.getAllRules(friendPaths)) {
      if (shouldCompileAgainstAbis && rule instanceof HasJavaAbi) {
        Optional<BuildTarget> abiJarTarget = ((HasJavaAbi) rule).getAbiJar();
        if (abiJarTarget.isPresent()) {
          Optional<BuildRule> abiJarRule = buildRuleResolver.getRuleOptional(abiJarTarget.get());
          if (abiJarRule.isPresent()) {
            SourcePath abiJarPath = abiJarRule.get().getSourcePathToOutput();
            if (abiJarPath != null) {
              sourcePaths.add(abiJarPath);
              continue;
            }
          }
        }
      }

      SourcePath fullJarPath = rule.getSourcePathToOutput();
      if (fullJarPath != null) {
        sourcePaths.add(fullJarPath);
      }
    }

    return sourcePaths.build();
  }
}
