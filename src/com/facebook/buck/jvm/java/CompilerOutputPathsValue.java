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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.jvm.core.BuildTargetValue;

/**
 * Value object that contains {@link CompilerOutputPaths} for library, source abi and source only
 * abi targets as well as library target fully qualified name.
 */
@BuckStyleValue
public abstract class CompilerOutputPathsValue {

  abstract String getLibraryTargetFullyQualifiedName();

  abstract CompilerOutputPaths getLibraryCompilerOutputPath();

  abstract CompilerOutputPaths getSourceAbiCompilerOutputPath();

  abstract CompilerOutputPaths getSourceOnlyAbiCompilerOutputPath();

  CompilerOutputPaths getByBuildTarget(BuildTargetValue buildTargetValue) {
    if (buildTargetValue.isLibraryJar()) {
      return getLibraryCompilerOutputPath();
    }

    if (buildTargetValue.isSourceAbi()) {
      return getSourceAbiCompilerOutputPath();
    }

    if (buildTargetValue.isSourceOnlyAbi()) {
      return getSourceOnlyAbiCompilerOutputPath();
    }

    throw new IllegalStateException(buildTargetValue + " is not supported");
  }

  /** Creates {@link CompilerOutputPathsValue} */
  public static CompilerOutputPathsValue of(
      String libraryTargetFullyQualifiedName,
      CompilerOutputPaths libraryCompilerOutputPath,
      CompilerOutputPaths sourceAbiCompilerOutputPath,
      CompilerOutputPaths sourceOnlyAbiCompilerOutputPath) {
    return ImmutableCompilerOutputPathsValue.ofImpl(
        libraryTargetFullyQualifiedName,
        libraryCompilerOutputPath,
        sourceAbiCompilerOutputPath,
        sourceOnlyAbiCompilerOutputPath);
  }

  /** Creates {@link CompilerOutputPathsValue} */
  public static CompilerOutputPathsValue of(
      BaseBuckPaths baseBuckPaths, BuildTargetValue buildTargetValue) {
    BuildTargetValue libraryTarget =
        buildTargetValue.isLibraryJar()
            ? buildTargetValue
            : BuildTargetValue.libraryTarget(buildTargetValue);

    CompilerOutputPaths libraryCompilerOutputPaths =
        CompilerOutputPaths.of(libraryTarget, baseBuckPaths);
    CompilerOutputPaths sourceAbiCompilerOutputPaths =
        CompilerOutputPaths.of(BuildTargetValue.sourceAbiTarget(libraryTarget), baseBuckPaths);
    CompilerOutputPaths sourceOnlyAbiCompilerOutputPaths =
        CompilerOutputPaths.of(BuildTargetValue.sourceOnlyAbiTarget(libraryTarget), baseBuckPaths);
    CompilerOutputPathsValue compilerOutputPathsValue =
        of(
            libraryTarget.getFullyQualifiedName(),
            libraryCompilerOutputPaths,
            sourceAbiCompilerOutputPaths,
            sourceOnlyAbiCompilerOutputPaths);
    return compilerOutputPathsValue;
  }
}
