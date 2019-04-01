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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableCollection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

public class ScalaConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final ScalaBuckConfig scalaBuckConfig;
  private final Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier;
  private @Nullable Tool scalac;
  private final JavacFactory javacFactory;

  public ScalaConfiguredCompilerFactory(ScalaBuckConfig config, JavacFactory javacFactory) {
    this(config, (toolchainProvider) -> ExtraClasspathProvider.EMPTY, javacFactory);
  }

  public ScalaConfiguredCompilerFactory(
      ScalaBuckConfig config,
      Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    this.scalaBuckConfig = config;
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
  public CompileToJarStepFactory configure(
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
        getJavac(buildRuleResolver, arg),
        javacOptions,
        extraClasspathProviderSupplier.apply(toolchainProvider));
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

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return javacFactory.create(new SourcePathRuleFinder(resolver), arg);
  }
}
