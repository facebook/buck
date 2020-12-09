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
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.JavaAbis;
import com.google.common.base.Preconditions;
import java.nio.file.FileSystem;
import java.util.Optional;

/** Provides access to the various output paths for a java library. */
@BuckStyleValueWithBuilder
public abstract class CompilerOutputPaths {

  public abstract RelPath getClassesDir();

  public abstract RelPath getOutputJarDirPath();

  public abstract Optional<RelPath> getAbiJarPath();

  public abstract RelPath getAnnotationPath();

  public abstract RelPath getPathToSourcesList();

  public abstract RelPath getWorkingDirectory();

  public abstract Optional<RelPath> getOutputJarPath();

  /** Creates {@link CompilerOutputPaths} */
  public static CompilerOutputPaths of(BuildTarget target, BaseBuckPaths buckPath) {
    return of(BuildTargetValue.of(target, buckPath), buckPath);
  }

  /** Creates {@link CompilerOutputPaths} */
  public static CompilerOutputPaths of(BuildTargetValue target, BaseBuckPaths buckPath) {
    FileSystem fileSystem = buckPath.getFileSystem();
    RelPath genDir = buckPath.getGenDir();
    RelPath scratchDir = buckPath.getScratchDir();
    RelPath annotationDir = buckPath.getAnnotationDir();

    RelPath genRoot = getRelativePath(target, "lib__%s__output", fileSystem, genDir);
    RelPath scratchRoot = getRelativePath(target, "lib__%s__scratch", fileSystem, scratchDir);

    return ImmutableCompilerOutputPaths.builder()
        .setClassesDir(scratchRoot.resolveRel("classes"))
        .setOutputJarDirPath(genRoot)
        .setAbiJarPath(
            target.hasAbiJar()
                ? Optional.of(
                    genRoot.resolveRel(String.format("%s-abi.jar", target.getShortName())))
                : Optional.empty())
        .setOutputJarPath(
            target.isLibraryJar()
                ? Optional.of(
                    genRoot.resolveRel(
                        String.format("%s.jar", target.getShortNameAndFlavorPostfix())))
                : Optional.empty())
        .setAnnotationPath(getRelativePath(target, "__%s_gen__", fileSystem, annotationDir))
        .setPathToSourcesList(getRelativePath(target, "__%s__srcs", fileSystem, genDir))
        .setWorkingDirectory(
            getRelativePath(target, "lib__%s__working_directory", fileSystem, genDir))
        .build();
  }

  /** Returns a path to a file that contains dependencies used in the compilation */
  public static RelPath getDepFilePath(BuildTarget target, BaseBuckPaths buckPath) {
    return getDepFilePath(BuildTargetValue.of(target, buckPath), buckPath);
  }

  /** Returns a path to a file that contains dependencies used in the compilation */
  public static RelPath getDepFilePath(BuildTargetValue target, BaseBuckPaths buckPath) {
    return CompilerOutputPaths.of(target, buckPath)
        .getOutputJarDirPath()
        .resolveRel("used-classes.json");
  }

  public static RelPath getClassesDir(BuildTarget target, BaseBuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getClassesDir();
  }

  public static RelPath getAnnotationPath(BuildTarget target, BaseBuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getAnnotationPath();
  }

  public static RelPath getAnnotationPath(BuildTargetValue target, BaseBuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getAnnotationPath();
  }

  public static RelPath getAbiJarPath(BuildTarget buildTarget, BaseBuckPaths buckPaths) {
    Preconditions.checkArgument(hasAbiJar(buildTarget));
    return CompilerOutputPaths.of(buildTarget, buckPaths).getAbiJarPath().get();
  }

  public static RelPath getAbiJarPath(BuildTargetValue buildTargetValue, BaseBuckPaths buckPaths) {
    Preconditions.checkArgument(buildTargetValue.hasAbiJar());
    return CompilerOutputPaths.of(buildTargetValue, buckPaths).getAbiJarPath().get();
  }

  public static RelPath getOutputJarPath(BuildTarget target, BaseBuckPaths buckPaths) {
    return CompilerOutputPaths.of(target, buckPaths).getOutputJarPath().get();
  }

  public static RelPath getOutputJarPath(
      BuildTargetValue buildTargetValue, BaseBuckPaths buckPaths) {
    return CompilerOutputPaths.of(buildTargetValue, buckPaths).getOutputJarPath().get();
  }

  private static boolean hasAbiJar(BuildTarget target) {
    return JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target);
  }

  /** Returns annotation path for the given {@code target} and {@code format} */
  public static RelPath getAnnotationPath(
      BaseBuckPaths buckPaths, BuildTargetValue target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getFileSystem(), buckPaths.getAnnotationDir());
  }

  /** Returns `gen` directory path for the given {@code target} and {@code format} */
  public static RelPath getGenPath(
      BaseBuckPaths buckPaths, BuildTargetValue target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");

    return getRelativePath(target, format, buckPaths.getFileSystem(), buckPaths.getGenDir());
  }

  private static RelPath getRelativePath(
      BuildTargetValue target, String format, FileSystem fileSystem, RelPath directory) {
    return directory.resolve(getBasePath(target, format).toRelPath(fileSystem));
  }

  private static ForwardRelativePath getBasePath(BuildTargetValue target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");
    return target
        .getBasePathForBaseName()
        .resolve(BuildTargetPaths.formatLastSegment(format, target.getShortNameAndFlavorPostfix()));
  }
}
