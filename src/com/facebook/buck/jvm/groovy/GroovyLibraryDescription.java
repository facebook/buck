/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public class GroovyLibraryDescription
    implements DescriptionWithTargetGraph<GroovyLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final GroovyBuckConfig groovyBuckConfig;
  private final JavaBuckConfig javaBuckConfig;

  public GroovyLibraryDescription(
      ToolchainProvider toolchainProvider,
      GroovyBuckConfig groovyBuckConfig,
      JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.groovyBuckConfig = groovyBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<GroovyLibraryDescriptionArg> getConstructorArgType() {
    return GroovyLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GroovyLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            projectFilesystem,
            graphBuilder,
            args);
    DefaultJavaLibraryRules defaultJavaLibraryRules =
        new DefaultJavaLibraryRules.Builder(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                context.getCellPathResolver(),
                new GroovyConfiguredCompilerFactory(groovyBuckConfig),
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .build();

    return JavaAbis.isAbiTarget(buildTarget)
        ? defaultJavaLibraryRules.buildAbi()
        : graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    // Groovyc may not play nice with source ABIs, so turning it off
    @Override
    default Optional<AbiGenerationMode> getAbiGenerationMode() {
      return Optional.of(AbiGenerationMode.CLASS);
    }

    ImmutableList<String> getExtraGroovycArguments();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGroovyLibraryDescriptionArg extends CoreArg {}
}
