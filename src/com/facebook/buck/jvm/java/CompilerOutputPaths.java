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
  public static CompilerOutputPaths of(BuildTarget target, BuckPaths buckPath) {
    boolean shouldIncludeTargetConfigHash =
        buckPath.shouldIncludeTargetConfigHash(target.getCellRelativeBasePath());
    RelPath genDir = buckPath.getGenDir();
    RelPath scratchDir = buckPath.getScratchDir();
    RelPath annotationDir = buckPath.getAnnotationDir();

    RelPath genRoot =
        BuildTargetPaths.getRelativePath(
            target, "lib__%s__output", shouldIncludeTargetConfigHash, genDir);
    RelPath scratchRoot =
        BuildTargetPaths.getRelativePath(
            target, "lib__%s__scratch", shouldIncludeTargetConfigHash, scratchDir);

    return ImmutableCompilerOutputPaths.builder()
        .setClassesDir(scratchRoot.resolveRel("classes"))
        .setOutputJarDirPath(genRoot)
        .setAbiJarPath(
            JavaAbis.hasAbi(target)
                ? Optional.of(
                    genRoot.resolveRel(String.format("%s-abi.jar", target.getShortName())))
                : Optional.empty())
        .setOutputJarPath(
            JavaAbis.isLibraryTarget(target)
                ? Optional.of(
                    genRoot.resolveRel(
                        String.format("%s.jar", target.getShortNameAndFlavorPostfix())))
                : Optional.empty())
        .setAnnotationPath(
            BuildTargetPaths.getRelativePath(
                target, "__%s_gen__", shouldIncludeTargetConfigHash, annotationDir))
        .setPathToSourcesList(
            BuildTargetPaths.getRelativePath(
                target, "__%s__srcs", shouldIncludeTargetConfigHash, genDir))
        .setWorkingDirectory(
            BuildTargetPaths.getRelativePath(
                target, "lib__%s__working_directory", shouldIncludeTargetConfigHash, genDir))
        .build();
  }

  /** Returns a path to a file that contains dependencies used in the compilation */
  public static RelPath getDepFilePath(RelPath outputJarDirPath) {
    return outputJarDirPath.resolveRel("used-classes.json");
  }

  public static ImmutableCompilerOutputPaths.Builder builder() {
    return ImmutableCompilerOutputPaths.builder();
  }
}
