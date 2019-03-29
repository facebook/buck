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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import java.util.function.Function;
import javax.annotation.Nullable;

public class JavaConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final JavaBuckConfig javaBuckConfig;
  private final Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier;
  private final JavacFactory javacFactory;

  public JavaConfiguredCompilerFactory(JavaBuckConfig javaBuckConfig, JavacFactory javacFactory) {
    this(javaBuckConfig, (toolchainProvider) -> ExtraClasspathProvider.EMPTY, javacFactory);
  }

  public JavaConfiguredCompilerFactory(
      JavaBuckConfig javaBuckConfig,
      Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.extraClasspathProviderSupplier = extraClasspathProviderSupplier;
    this.javacFactory = javacFactory;
  }

  @Override
  public boolean trackClassUsage(JavacOptions javacOptions) {
    return javacOptions.trackClassUsage();
  }

  @Override
  public boolean shouldDesugarInterfaceMethods() {
    return javaBuckConfig.shouldDesugarInterfaceMethods();
  }

  @Override
  public boolean shouldCompileAgainstAbis() {
    return javaBuckConfig.shouldCompileAgainstAbis();
  }

  @Override
  public boolean shouldGenerateSourceAbi() {
    return javaBuckConfig.getAbiGenerationMode().isSourceAbi();
  }

  @Override
  public boolean shouldMigrateToSourceOnlyAbi() {
    return shouldGenerateSourceAbi();
  }

  @Override
  public boolean shouldGenerateSourceOnlyAbi() {
    return shouldGenerateSourceAbi() && !javaBuckConfig.getAbiGenerationMode().usesDependencies();
  }

  @Override
  public CompileToJarStepFactory configure(
      @Nullable JvmLibraryArg arg,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {

    return new JavacToJarStepFactory(
        getJavac(buildRuleResolver, arg),
        javacOptions,
        extraClasspathProviderSupplier.apply(toolchainProvider));
  }

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return javacFactory.create(new SourcePathRuleFinder(resolver), arg);
  }
}
