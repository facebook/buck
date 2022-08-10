/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static com.facebook.buck.jvm.kotlin.DaemonKotlincToJarStepFactory.getAnnotationProcessors;
import static com.facebook.buck.jvm.kotlin.DaemonKotlincToJarStepFactory.getKaptAnnotationProcessors;
import static com.facebook.buck.jvm.kotlin.DaemonKotlincToJarStepFactory.getKspAnnotationProcessors;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.CoreArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

public class KotlinConfiguredCompilerFactory extends ConfiguredCompilerFactory {

  private final KotlinBuckConfig kotlinBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
      extraClasspathProviderSupplier;
  private final JavacFactory javacFactory;

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig,
      DownwardApiConfig downwardApiConfig,
      JavacFactory javacFactory) {
    this(
        kotlinBuckConfig,
        downwardApiConfig,
        (toolchainProvider, toolchainTargetConfiguration) -> ExtraClasspathProvider.EMPTY,
        javacFactory);
  }

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig,
      DownwardApiConfig downwardApiConfig,
      BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
          extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    super();
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.extraClasspathProviderSupplier = extraClasspathProviderSupplier;
    this.javacFactory = javacFactory;
  }

  @Override
  public BaseCompileToJarStepFactory<KotlinExtraParams> configure(
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {
    CoreArg kotlinArgs = Objects.requireNonNull((CoreArg) args);
    return new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinc(),
        kotlinBuckConfig.getKotlinHomeLibraries(targetConfiguration),
        kotlinBuckConfig.getPathToStdlibJar(targetConfiguration),
        kotlinBuckConfig.getPathToAnnotationProcessingJar(targetConfiguration),
        kotlinArgs.getExtraKotlincArguments(),
        kotlinArgs.getKotlinCompilerPlugins(),
        getFriendSourcePaths(buildRuleResolver, kotlinArgs.getFriendPaths(), kotlinBuckConfig),
        kotlinArgs.getAnnotationProcessingTool().orElse(AnnotationProcessingTool.KAPT),
        kotlinArgs.getTarget().map(this::getKotlincCompatibleTarget),
        extraClasspathProviderSupplier.apply(toolchainProvider, targetConfiguration),
        javacOptions,
        downwardApiConfig.isEnabledForKotlin(),
        kotlinBuckConfig.shouldGenerateAnnotationProcessingStats(),
        Kosabi.getPluginOptionsMappings(
            targetConfiguration, KosabiConfig.of(kotlinBuckConfig.getDelegate())),
        kotlinArgs
            .getAbiGenerationMode()
            .map(mode -> mode.equals(AbiGenerationMode.SOURCE_ONLY))
            .orElse(false));
  }

  @Override
  public Optional<ExtraClasspathProvider> getExtraClasspathProvider(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    return Optional.ofNullable(
        extraClasspathProviderSupplier.apply(toolchainProvider, toolchainTargetConfiguration));
  }

  @Override
  public Javac getJavac(
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
    // Kotlin does not support source-abi
    return false;
  }

  @Override
  public boolean shouldGenerateSourceOnlyAbi() {
    // Kotlin supports source-only-abi via Kosabi, a group of compiler plugins (stubsgen,
    // jvm-abi-gen)
    //
    // Several aspects impact flavor choice:
    // 1. bcfg used by `KotlinBuckConfig` (class is a default)
    //   1a. For roll-out we want to keep a default behaviour in bcfg.
    //   1b. We don't want to change bcfg to have easy opt-in/out for each rule.
    // 2. Rule args `abi_generation_mode = ...`
    //   2a. We will turn source-only-abi on/off for each rule separately passing
    // `abi_generation_mode = source_only`.
    //
    // We're not using a config based declaration
    // `usesDependencies(kotlinBuckConfig.getAbiGenerationMode())`
    // b/c it's going to be always false for Kotlin.
    return true;
  }

  @Override
  public boolean trackClassUsage(JavacOptions javacOptions) {
    JavacPluginParams annotationProcessorParams = javacOptions.getJavaAnnotationProcessorParams();
    ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
        getAnnotationProcessors(annotationProcessorParams);
    return kotlinBuckConfig.trackClassUsage()
        && (kotlinBuckConfig.trackClassUsageForKaptTargets()
            || getKaptAnnotationProcessors(annotationProcessors).isEmpty())
        && (kotlinBuckConfig.trackClassUsageForKspTargets()
            || getKspAnnotationProcessors(annotationProcessors).isEmpty())
        && annotationProcessors.stream()
            .flatMap(prop -> prop.getProcessorNames().stream())
            .noneMatch(name -> kotlinBuckConfig.trackClassUsageProcessorBlocklist().contains(name));
  }

  @Override
  public JavaBuckConfig.UnusedDependenciesConfig getUnusedDependenciesAction() {
    return kotlinBuckConfig.getUnusedDependenciesAction();
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

  private String getKotlincCompatibleTarget(String target) {
    if (target.equals("6")) {
      return "1.6";
    }

    if (target.equals("8")) {
      return "1.8";
    }

    return target;
  }
}
