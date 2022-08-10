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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Collectors;

/** Extra params for creating Kotlin compile steps. */
@BuckStyleValue
public abstract class KotlinExtraParams implements CompileToJarStepFactory.ExtraParams {

  public abstract AbsPath getResolvedStandardLibraryClassPath();

  public abstract AbsPath getResolvedAnnotationProcessingClassPath();

  public abstract ImmutableList<String> getExtraKotlincArguments();

  public abstract ImmutableMap<AbsPath, ImmutableMap<String, String>>
      getResolvedKotlinCompilerPlugins();

  public abstract ImmutableMap<String, AbsPath> getResolvedKosabiPluginOptionPath();

  public abstract ImmutableSortedSet<AbsPath> getResolvedFriendPaths();

  public abstract ImmutableSortedSet<AbsPath> getResolvedKotlinHomeLibraries();

  public abstract ResolvedJavacOptions getResolvedJavacOptions();

  public abstract Optional<String> getJvmTarget();

  public abstract boolean shouldGenerateAnnotationProcessingStats();

  public abstract boolean shouldVerifySourceOnlyAbiConstraints();

  /** Resolve extra params. */
  public static KotlinExtraParams of(
      SourcePathResolverAdapter resolver,
      AbsPath rootPath,
      SourcePath standardLibraryClassPath,
      SourcePath annotationProcessingClassPath,
      ImmutableList<String> extraKotlincArguments,
      ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins,
      ImmutableMap<String, SourcePath> kosabiPluginOptions,
      ImmutableList<SourcePath> friendPaths,
      ImmutableSortedSet<SourcePath> kotlinHomeLibraries,
      JavacOptions javacOptions,
      Optional<String> jvmTarget,
      boolean shouldGenerateAnnotationProcessingStats,
      boolean shouldVerifySourceOnlyAbiConstraints) {
    return ImmutableKotlinExtraParams.ofImpl(
        resolver.getAbsolutePath(standardLibraryClassPath),
        resolver.getAbsolutePath(annotationProcessingClassPath),
        extraKotlincArguments,
        kotlinCompilerPlugins.entrySet().stream()
            .collect(
                Collectors.toMap(
                    // RelPath does not appear to work if path is a BuildTargetSourcePath in a
                    // different cell than the kotlin_library rule being defined.
                    e -> resolver.getAbsolutePath(e.getKey()),
                    e -> e.getValue())),
        kosabiPluginOptions.entrySet().stream()
            .collect(
                Collectors.toMap(e -> e.getKey(), e -> resolver.getAbsolutePath(e.getValue()))),
        resolver.getAllAbsolutePaths(friendPaths),
        resolver.getAllAbsolutePaths(kotlinHomeLibraries),
        ResolvedJavacOptions.of(javacOptions, resolver, rootPath),
        jvmTarget,
        shouldGenerateAnnotationProcessingStats,
        shouldVerifySourceOnlyAbiConstraints);
  }
}
