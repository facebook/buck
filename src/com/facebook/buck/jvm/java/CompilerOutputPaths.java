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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.jvm.core.JavaAbis;
import com.google.common.base.Preconditions;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Optional;

/** Provides access to the various output paths for a java library. */
@BuckStyleValueWithBuilder
public abstract class CompilerOutputPaths {

  public abstract RelPath getClassesDir();

  public abstract Path getOutputJarDirPath();

  public abstract Optional<Path> getAbiJarPath();

  public abstract RelPath getAnnotationPath();

  public abstract Path getPathToSourcesList();

  public abstract Path getWorkingDirectory();

  public abstract Optional<Path> getOutputJarPath();

  /** Creates {@link CompilerOutputPaths} */
  public static CompilerOutputPaths of(BuildTarget target, BuckPaths buckPath) {
    FileSystem fileSystem = buckPath.getFileSystem();
    boolean includeTargetConfigHash = buckPath.shouldIncludeTargetConfigHash();
    RelPath genDir = buckPath.getGenDir();
    RelPath scratchDir = buckPath.getScratchDir();
    RelPath annotationDir = buckPath.getAnnotationDir();

    RelPath genRoot =
        BuildTargetPaths.getRelativePath(
            target, "lib__%s__output", fileSystem, includeTargetConfigHash, genDir);
    RelPath scratchRoot =
        BuildTargetPaths.getRelativePath(
            target, "lib__%s__scratch", fileSystem, includeTargetConfigHash, scratchDir);

    return ImmutableCompilerOutputPaths.builder()
        .setClassesDir(scratchRoot.resolveRel("classes"))
        .setOutputJarDirPath(genRoot.getPath())
        .setAbiJarPath(
            hasAbiJar(target)
                ? Optional.of(genRoot.resolve(String.format("%s-abi.jar", target.getShortName())))
                : Optional.empty())
        .setOutputJarPath(
            isLibraryJar(target)
                ? Optional.of(
                    genRoot.resolve(String.format("%s.jar", target.getShortNameAndFlavorPostfix())))
                : Optional.empty())
        .setAnnotationPath(
            BuildTargetPaths.getRelativePath(
                target, "__%s_gen__", fileSystem, includeTargetConfigHash, annotationDir))
        .setPathToSourcesList(
            BuildTargetPaths.getRelativePath(
                    target, "__%s__srcs", fileSystem, includeTargetConfigHash, genDir)
                .getPath())
        .setWorkingDirectory(
            BuildTargetPaths.getRelativePath(
                    target,
                    "lib__%s__working_directory",
                    fileSystem,
                    includeTargetConfigHash,
                    genDir)
                .getPath())
        .build();
  }

  /** Returns a path to a file that contains dependencies used in the compilation */
  public static Path getDepFilePath(BuildTarget target, BuckPaths buckPath) {
    return CompilerOutputPaths.of(target, buckPath)
        .getOutputJarDirPath()
        .resolve("used-classes.json");
  }

  public static RelPath getClassesDir(BuildTarget target, BuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getClassesDir();
  }

  public static RelPath getAnnotationPath(BuildTarget target, BuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getAnnotationPath();
  }

  public static Path getAbiJarPath(BuildTarget buildTarget, BuckPaths buckPaths) {
    Preconditions.checkArgument(hasAbiJar(buildTarget));
    return CompilerOutputPaths.of(buildTarget, buckPaths).getAbiJarPath().get();
  }

  public static Path getOutputJarPath(BuildTarget target, BuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getOutputJarPath().get();
  }

  private static boolean isLibraryJar(BuildTarget target) {
    return JavaAbis.isLibraryTarget(target);
  }

  private static boolean hasAbiJar(BuildTarget target) {
    return JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target);
  }
}
