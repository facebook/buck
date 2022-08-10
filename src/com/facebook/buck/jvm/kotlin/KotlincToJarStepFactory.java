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

import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

/**
 * Factory that creates Kotlin related compile build steps, and is capable of creating the extra
 * parameters necessary to produce those steps.
 *
 * <p>Note that the logic to build a Kotlin target is found in {@link
 * DaemonKotlincToJarStepFactory}, and most Kotlin-specific build parameters must pass from this
 * class to that class via an instance of {@link KotlinExtraParams}. This abstraction exists because
 * the hand-over could happen in the same process, or across processes when using KotlinCD, where
 * all extra parameters must be serialized and deserialized (they cannot be passed by reference). To
 * add a new parameter:
 *
 * <ol>
 *   <li>Introduce it as a constructor parameter here, and add it to the rulekey.
 *   <li>Include the parameter in {@link KotlinExtraParams} and pass it through in {@link
 *       KotlincToJarStepFactory#createExtraParams(BuildContext, AbsPath)}.
 *   <li>Use it within {@link DaemonKotlincToJarStepFactory#createCompileStep( FilesystemParams,
 *       ImmutableMap, BuildTargetValue, CompilerOutputPathsValue, CompilerParameters,
 *       ImmutableList.Builder, BuildableContext, ResolvedJavac, KotlinExtraParams)}
 * </ol>
 *
 * <p>DO NOT add it as a constructor parameter to {@link DaemonKotlincToJarStepFactory} and pass it
 * directly through.
 */
public class KotlincToJarStepFactory extends DaemonKotlincToJarStepFactory
    implements CompileToJarStepFactory.CreatesExtraParams<KotlinExtraParams> {

  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final SourcePath standardLibraryClasspath;
  @AddToRuleKey private final SourcePath annotationProcessingClassPath;

  @AddToRuleKey
  private final ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins;

  @AddToRuleKey private final ImmutableList<SourcePath> friendPaths;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> kotlinHomeLibraries;

  @AddToRuleKey private final ImmutableMap<String, SourcePath> kosabiPluginOptionsMappings;

  KotlincToJarStepFactory(
      Kotlinc kotlinc,
      ImmutableSortedSet<SourcePath> kotlinHomeLibraries,
      SourcePath standardLibraryClasspath,
      SourcePath annotationProcessingClassPath,
      ImmutableList<String> extraKotlincArguments,
      ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins,
      ImmutableList<SourcePath> friendPaths,
      AnnotationProcessingTool annotationProcessingTool,
      Optional<String> jvmTarget,
      ExtraClasspathProvider extraClasspathProvider,
      JavacOptions javacOptions,
      boolean withDownwardApi,
      boolean shouldGenerateAnnotationProcessingStats,
      ImmutableMap<String, SourcePath> kosabiPluginOptionsMappings,
      boolean verifySourceOnlyAbiConstraints) {
    super(
        kotlinc,
        extraKotlincArguments,
        annotationProcessingTool,
        jvmTarget,
        extraClasspathProvider,
        BaseCompileToJarStepFactory.hasAnnotationProcessing(javacOptions),
        withDownwardApi,
        shouldGenerateAnnotationProcessingStats,
        verifySourceOnlyAbiConstraints);
    this.javacOptions = javacOptions;
    this.kotlinHomeLibraries = kotlinHomeLibraries;
    this.standardLibraryClasspath = standardLibraryClasspath;
    this.annotationProcessingClassPath = annotationProcessingClassPath;
    this.kotlinCompilerPlugins = kotlinCompilerPlugins;
    this.friendPaths = friendPaths;
    this.extraClasspathProvider = extraClasspathProvider;
    this.kosabiPluginOptionsMappings = kosabiPluginOptionsMappings;
  }

  @Override
  public KotlinExtraParams createExtraParams(BuildContext context, AbsPath rootPath) {
    return KotlinExtraParams.of(
        context.getSourcePathResolver(),
        rootPath,
        standardLibraryClasspath,
        annotationProcessingClassPath,
        kotlinCompilerPlugins,
        kosabiPluginOptionsMappings,
        friendPaths,
        kotlinHomeLibraries,
        javacOptions.withBootclasspathFromContext(extraClasspathProvider));
  }

  @Override
  protected Optional<String> getBootClasspath() {
    return javacOptions.withBootclasspathFromContext(extraClasspathProvider).getBootclasspath();
  }
}
