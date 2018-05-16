/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.ConfiguredCompiler;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.base.Preconditions;
import java.util.Optional;
import javax.annotation.Nullable;

public class GroovyConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final GroovyBuckConfig groovyBuckConfig;

  public GroovyConfiguredCompilerFactory(GroovyBuckConfig groovyBuckConfig) {
    this.groovyBuckConfig = groovyBuckConfig;
  }

  @Override
  public ConfiguredCompiler configure(
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      ToolchainProvider toolchainProvider) {
    GroovyLibraryDescription.CoreArg groovyArgs =
        (GroovyLibraryDescription.CoreArg) Preconditions.checkNotNull(args);
    return new GroovycToJarStepFactory(
        sourcePathResolver,
        ruleFinder,
        projectFilesystem,
        Preconditions.checkNotNull(groovyBuckConfig).getGroovyCompiler().get(),
        Optional.of(groovyArgs.getExtraGroovycArguments()),
        javacOptions);
  }
}
