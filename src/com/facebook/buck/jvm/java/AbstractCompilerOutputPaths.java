/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Provides access to the various output paths for a java library. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCompilerOutputPaths {
  public abstract Path getClassesDir();

  public abstract Path getOutputJarDirPath();

  public abstract Optional<Path> getAbiJarPath();

  public abstract Path getAnnotationPath();

  public abstract Path getPathToSourcesList();

  public abstract Path getWorkingDirectory();

  public abstract Optional<Path> getOutputJarPath();

  public static CompilerOutputPaths of(BuildTarget target, ProjectFilesystem filesystem) {
    Path genRoot = BuildTargetPaths.getGenPath(filesystem, target, "lib__%s__output");
    Path scratchRoot = BuildTargetPaths.getScratchPath(filesystem, target, "lib__%s__scratch");

    return CompilerOutputPaths.builder()
        .setClassesDir(scratchRoot.resolve("classes"))
        .setOutputJarDirPath(genRoot)
        .setAbiJarPath(
            hasAbiJar(target)
                ? Optional.of(genRoot.resolve(String.format("%s-abi.jar", target.getShortName())))
                : Optional.empty())
        .setOutputJarPath(
            isLibraryJar(target)
                ? Optional.of(
                    genRoot.resolve(String.format("%s.jar", target.getShortNameAndFlavorPostfix())))
                : Optional.empty())
        .setAnnotationPath(BuildTargetPaths.getAnnotationPath(filesystem, target, "__%s_gen__"))
        .setPathToSourcesList(BuildTargetPaths.getGenPath(filesystem, target, "__%s__srcs"))
        .setWorkingDirectory(
            BuildTargetPaths.getGenPath(filesystem, target, "lib__%s__working_directory"))
        .build();
  }

  public static Path getDepFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem)
        .getOutputJarDirPath()
        .resolve("used-classes.json");
  }

  public static Path getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getClassesDir();
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getOutputJarDirPath();
  }

  public static Optional<Path> getAnnotationPath(ProjectFilesystem filesystem, BuildTarget target) {
    return Optional.of(CompilerOutputPaths.of(target, filesystem).getAnnotationPath());
  }

  public static Path getAbiJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    Preconditions.checkArgument(hasAbiJar(buildTarget));
    return CompilerOutputPaths.of(buildTarget, filesystem).getAbiJarPath().get();
  }

  public static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(target, filesystem).getOutputJarPath().get();
  }

  private static boolean isLibraryJar(BuildTarget target) {
    return JavaAbis.isLibraryTarget(target);
  }

  private static boolean hasAbiJar(BuildTarget target) {
    return JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target);
  }
}
