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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import javax.annotation.Nullable;

public class JavaConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final JavaBuckConfig javaBuckConfig;
  private final ExtraClasspathProvider extraClasspathProvider;

  public JavaConfiguredCompilerFactory(JavaBuckConfig javaBuckConfig) {
    this(javaBuckConfig, ExtraClasspathProvider.EMPTY);
  }

  public JavaConfiguredCompilerFactory(
      JavaBuckConfig javaBuckConfig, ExtraClasspathProvider extraClasspathProvider) {
    this.javaBuckConfig = javaBuckConfig;
    this.extraClasspathProvider = extraClasspathProvider;
  }

  @Override
  public boolean trackClassUsage(JavacOptions javacOptions) {
    return javacOptions.trackClassUsage();
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
  public ConfiguredCompiler configure(
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      @Nullable JvmLibraryArg arg,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver) {

    return new JavacToJarStepFactory(
        sourcePathResolver,
        ruleFinder,
        projectFilesystem,
        getJavac(buildRuleResolver, arg),
        javacOptions,
        extraClasspathProvider);
  }

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return JavacFactory.create(new SourcePathRuleFinder(resolver), javaBuckConfig, arg);
  }
}
