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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
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

  public abstract Path getDepFilePath();

  public abstract Path getPathToSourcesList();

  @Nullable
  public abstract Path getAbiJarPath();

  @Value.Default
  public boolean shouldTrackClassUsage() {
    return false;
  }

  @Value.Default
  public boolean shouldGenerateAbiJar() {
    return false;
  }

  @Value.Default
  public boolean ruleIsRequiredForSourceAbi() {
    return false;
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
    if (HasJavaAbi.isLibraryTarget(buildTarget)) {
      buildTarget = HasJavaAbi.getSourceAbiJar(buildTarget);
    }
    Preconditions.checkArgument(HasJavaAbi.isSourceAbiTarget(buildTarget));

    return BuildTargets.getGenPath(projectFilesystem, buildTarget, "lib__%s__output")
        .resolve(String.format("%s-abi.jar", buildTarget.getShortName()));
  }

  public abstract static class Builder {
    public CompilerParameters.Builder setStandardPaths(
        BuildTarget target, ProjectFilesystem projectFilesystem) {
      CompilerParameters.Builder builder = (CompilerParameters.Builder) this;

      return builder
          .setWorkingDirectory(
              BuildTargets.getGenPath(projectFilesystem, target, "lib__%s____working_directory"))
          .setGeneratedCodeDirectory(getAnnotationPath(projectFilesystem, target).get())
          .setDepFilePath(
              getOutputJarDirPath(target, projectFilesystem).resolve("used-classes.json"))
          .setPathToSourcesList(BuildTargets.getGenPath(projectFilesystem, target, "__%s__srcs"))
          .setOutputDirectory(getClassesDir(target, projectFilesystem))
          .setAbiJarPath(getAbiJarPath(target, projectFilesystem));
    }

    public CompilerParameters.Builder setSourceFileSourcePaths(
        ImmutableSortedSet<SourcePath> srcs,
        ProjectFilesystem projectFilesystem,
        SourcePathResolver resolver) {
      ImmutableSortedSet<Path> javaSrcs =
          srcs.stream()
              .map(src -> projectFilesystem.relativize(resolver.getAbsolutePath(src)))
              .collect(MoreCollectors.toImmutableSortedSet());
      return ((CompilerParameters.Builder) this).setSourceFilePaths(javaSrcs);
    }

    public CompilerParameters.Builder setClasspathEntriesSourcePaths(
        ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
        SourcePathResolver resolver) {
      ImmutableSortedSet<Path> compileTimeClasspathPaths =
          compileTimeClasspathSourcePaths
              .stream()
              .map(resolver::getAbsolutePath)
              .collect(MoreCollectors.toImmutableSortedSet());
      return ((CompilerParameters.Builder) this).setClasspathEntries(compileTimeClasspathPaths);
    }
  }
}
