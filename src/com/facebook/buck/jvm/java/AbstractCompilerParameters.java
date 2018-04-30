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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTargets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCompilerParameters {
  @Value.Default
  public ImmutableSortedSet<Path> getSourceFilePaths() {
    return ImmutableSortedSet.of();
  }

  @Value.Default
  public ImmutableSortedSet<Path> getClasspathEntries() {
    return ImmutableSortedSet.of();
  }

  public abstract Path getOutputDirectory();

  public abstract Path getGeneratedCodeDirectory();

  public abstract Path getWorkingDirectory();

  public abstract Path getPathToSourcesList();

  @Value.Default
  public AbiGenerationMode getAbiGenerationMode() {
    return AbiGenerationMode.CLASS;
  }

  @Value.Default
  public AbiGenerationMode getAbiCompatibilityMode() {
    return getAbiGenerationMode();
  }

  @Value.Default
  public boolean shouldTrackClassUsage() {
    return false;
  }

  @Value.Default
  public boolean shouldTrackJavacPhaseEvents() {
    return false;
  }

  @Nullable
  public abstract SourceOnlyAbiRuleInfo getSourceOnlyAbiRuleInfo();

  public static Path getDepFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return getOutputJarDirPath(target, filesystem).resolve("used-classes.json");
  }

  public static Path getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, target, "lib__%s__classes");
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "lib__%s__output");
  }

  public static Optional<Path> getAnnotationPath(ProjectFilesystem filesystem, BuildTarget target) {
    return Optional.of(BuildTargets.getAnnotationPath(filesystem, target, "__%s_gen__"));
  }

  public static Path getAbiJarPath(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    Preconditions.checkArgument(
        HasJavaAbi.isSourceAbiTarget(buildTarget) || HasJavaAbi.isSourceOnlyAbiTarget(buildTarget));

    return BuildTargets.getGenPath(projectFilesystem, buildTarget, "lib__%s__output")
        .resolve(String.format("%s-abi.jar", buildTarget.getShortName()));
  }

  public abstract static class Builder {
    public CompilerParameters.Builder setScratchPaths(
        BuildTarget target, ProjectFilesystem projectFilesystem) {
      CompilerParameters.Builder builder = (CompilerParameters.Builder) this;

      return builder
          .setWorkingDirectory(
              BuildTargets.getGenPath(projectFilesystem, target, "lib__%s____working_directory"))
          .setGeneratedCodeDirectory(getAnnotationPath(projectFilesystem, target).get())
          .setPathToSourcesList(BuildTargets.getGenPath(projectFilesystem, target, "__%s__srcs"))
          .setOutputDirectory(getClassesDir(target, projectFilesystem));
    }

    public CompilerParameters.Builder setSourceFileSourcePaths(
        ImmutableSortedSet<SourcePath> srcs,
        ProjectFilesystem projectFilesystem,
        SourcePathResolver resolver) {
      ImmutableSortedSet<Path> javaSrcs =
          srcs.stream()
              .map(src -> projectFilesystem.relativize(resolver.getAbsolutePath(src)))
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      return ((CompilerParameters.Builder) this).setSourceFilePaths(javaSrcs);
    }

    public CompilerParameters.Builder setClasspathEntriesSourcePaths(
        ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
        SourcePathResolver resolver) {
      ImmutableSortedSet<Path> compileTimeClasspathPaths =
          compileTimeClasspathSourcePaths
              .stream()
              .map(resolver::getAbsolutePath)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      return ((CompilerParameters.Builder) this).setClasspathEntries(compileTimeClasspathPaths);
    }
  }
}
