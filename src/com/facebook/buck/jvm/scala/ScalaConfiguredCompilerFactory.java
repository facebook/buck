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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.java.BuildContextAwareExtraParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.google.common.collect.ImmutableCollection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class ScalaConfiguredCompilerFactory extends ConfiguredCompilerFactory {

  private final ScalaBuckConfig scalaBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
      extraClasspathProviderSupplier;
  private @Nullable Tool scalac;
  private final JavacFactory javacFactory;

  public ScalaConfiguredCompilerFactory(
      ScalaBuckConfig scalaBuckConfig,
      DownwardApiConfig downwardApiConfig,
      JavacFactory javacFactory) {
    this(
        scalaBuckConfig,
        downwardApiConfig,
        (toolchainProvider, toolchainTargetConfiguration) -> ExtraClasspathProvider.EMPTY,
        javacFactory);
  }

  public ScalaConfiguredCompilerFactory(
      ScalaBuckConfig scalaBuckConfig,
      DownwardApiConfig downwardApiConfig,
      BiFunction<ToolchainProvider, TargetConfiguration, ExtraClasspathProvider>
          extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    this.scalaBuckConfig = scalaBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.extraClasspathProviderSupplier = extraClasspathProviderSupplier;
    this.javacFactory = javacFactory;
  }

  private Tool getScalac(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    if (scalac == null) {
      scalac = scalaBuckConfig.getScalac(resolver, targetConfiguration);
    }
    return scalac;
  }

  @Override
  public CompileToJarStepFactory<BuildContextAwareExtraParams> configure(
      @Nullable JvmLibraryArg arg,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {
    return new ScalacToJarStepFactory(
        getScalac(buildRuleResolver, targetConfiguration),
        scalaBuckConfig.getCompilerFlags(),
        Objects.requireNonNull(arg).getExtraArguments(),
        buildRuleResolver.getAllRules(scalaBuckConfig.getCompilerPlugins(targetConfiguration)),
        javacOptions,
        extraClasspathProviderSupplier.apply(toolchainProvider, targetConfiguration),
        downwardApiConfig.isEnabledForScala());
  }

  @Override
  public Optional<ExtraClasspathProvider> getExtraClasspathProvider(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        extraClasspathProviderSupplier.apply(toolchainProvider, toolchainTargetConfiguration));
  }

  @Override
  public void addTargetDeps(
      TargetConfiguration targetConfiguration,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget(targetConfiguration))
        .addAll(scalaBuckConfig.getCompilerPlugins(targetConfiguration));
    Optionals.addIfPresent(scalaBuckConfig.getScalacTarget(targetConfiguration), extraDepsBuilder);
  }

  @Override
  public void getNonProvidedClasspathDeps(
      TargetConfiguration targetConfiguration, Consumer<BuildTarget> depsConsumer) {
    depsConsumer.accept(scalaBuckConfig.getScalaLibraryTarget(targetConfiguration));
  }

  @Override
  public Javac getJavac(
      BuildRuleResolver resolver,
      @Nullable JvmLibraryArg arg,
      TargetConfiguration toolchainTargetConfiguration) {
    return javacFactory.create(resolver, arg, toolchainTargetConfiguration);
  }
}
