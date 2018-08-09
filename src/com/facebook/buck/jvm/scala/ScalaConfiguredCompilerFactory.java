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
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.jvm.java.ConfiguredCompiler;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
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

  private Tool getScalac(BuildRuleResolver resolver) {
    if (scalac == null) {
      scalac = scalaBuckConfig.getScalac(resolver);
    }
    return scalac;
  }

  @Override
  public ConfiguredCompiler configure(
      @Nullable JvmLibraryArg arg,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      ToolchainProvider toolchainProvider) {

    return new ScalacToJarStepFactory(
        getScalac(buildRuleResolver),
        buildRuleResolver.getRule(scalaBuckConfig.getScalaLibraryTarget()),
        scalaBuckConfig.getCompilerFlags(),
        Preconditions.checkNotNull(arg).getExtraArguments(),
        buildRuleResolver.getAllRules(scalaBuckConfig.getCompilerPlugins()),
        getJavac(buildRuleResolver, arg),
        javacOptions,
        extraClasspathProviderSupplier.apply(toolchainProvider));
  }

  @Override
  public void addTargetDeps(
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins());
    Optionals.addIfPresent(scalaBuckConfig.getScalacTarget(), extraDepsBuilder);
  }

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return javacFactory.create(new SourcePathRuleFinder(resolver), arg);
  }
}
