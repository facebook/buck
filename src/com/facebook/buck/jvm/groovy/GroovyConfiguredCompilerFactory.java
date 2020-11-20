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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription.CoreArg;
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
import javax.annotation.Nullable;

public class GroovyConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final GroovyBuckConfig groovyBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final JavacFactory javacFactory;

  public GroovyConfiguredCompilerFactory(
      GroovyBuckConfig groovyBuckConfig,
      DownwardApiConfig downwardApiConfig,
      JavacFactory javacFactory) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.javacFactory = javacFactory;
  }

  @Override
  public CompileToJarStepFactory configure(
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {
    GroovyLibraryDescription.CoreArg groovyArgs = (CoreArg) Objects.requireNonNull(args);

    return new GroovycToJarStepFactory(
        groovyBuckConfig.getGroovyc(targetConfiguration),
        Optional.of(groovyArgs.getExtraGroovycArguments()),
        javacOptions,
        downwardApiConfig.isEnabledForGroovy());
  }

  @Override
  public Javac getJavac(
      BuildRuleResolver resolver,
      @Nullable JvmLibraryArg arg,
      TargetConfiguration toolchainTargetConfiguration) {
    return javacFactory.create(resolver, arg, toolchainTargetConfiguration);
  }

  @Override
  public Optional<ExtraClasspathProvider> getExtraClasspathProvider(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    return Optional.empty();
  }

  @Override
  public void addTargetDeps(
      TargetConfiguration targetConfiguration,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (groovyBuckConfig != null) {
      Optionals.addIfPresent(
          groovyBuckConfig.getGroovycTarget(targetConfiguration), extraDepsBuilder);
    }
  }
}
