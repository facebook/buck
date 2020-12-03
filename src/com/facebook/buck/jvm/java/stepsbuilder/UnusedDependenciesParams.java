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

package com.facebook.buck.jvm.java.stepsbuilder;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Parameters that used for {@link UnusedDependenciesFinder} creation. */
@BuckStyleValue
public abstract class UnusedDependenciesParams {

  abstract ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> getDeps();

  abstract ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> getProvidedDeps();

  abstract BaseBuckPaths getBuckPaths();

  abstract AbsPath getRootPath();

  abstract ImmutableMap<String, RelPath> getCellToPathMappings();

  abstract BuildTargetValue getBuildTargetValue();

  abstract JavaBuckConfig.UnusedDependenciesAction getUnusedDependenciesAction();

  abstract ImmutableList<String> getExportedDeps();

  abstract Optional<String> getBuildozerPath();

  abstract ImmutableSet<Optional<String>> getKnownCellNames();

  abstract boolean isOnlyPrintCommands();

  abstract boolean isDoUltralightChecking();

  /** Creates {@link UnusedDependenciesParams} */
  public static UnusedDependenciesParams of(
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> deps,
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> providedDeps,
      BaseBuckPaths buckPaths,
      AbsPath rootPath,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTargetValue buildTargetValue,
      JavaBuckConfig.UnusedDependenciesAction unusedDependenciesAction,
      ImmutableList<String> exportedDeps,
      Optional<String> buildozerPath,
      ImmutableSet<Optional<String>> knownCellNames,
      boolean onlyPrintCommands,
      boolean doUltralightChecking) {
    return ImmutableUnusedDependenciesParams.ofImpl(
        deps,
        providedDeps,
        buckPaths,
        rootPath,
        cellToPathMappings,
        buildTargetValue,
        unusedDependenciesAction,
        exportedDeps,
        buildozerPath,
        knownCellNames,
        onlyPrintCommands,
        doUltralightChecking);
  }

  /** Creates {@link UnusedDependenciesFinder} */
  public static UnusedDependenciesFinder createFinder(
      UnusedDependenciesParams unusedDependenciesParams) {

    AbsPath rootPath = unusedDependenciesParams.getRootPath();
    BuildTargetValue buildTargetValue = unusedDependenciesParams.getBuildTargetValue();
    Path depFilePath =
        CompilerOutputPaths.getDepFilePath(
            buildTargetValue, unusedDependenciesParams.getBuckPaths());
    RelPath depFile = rootPath.relativize(rootPath.resolve(depFilePath));

    return UnusedDependenciesFinder.of(
        buildTargetValue.getFullyQualifiedName(),
        unusedDependenciesParams.getDeps(),
        unusedDependenciesParams.getProvidedDeps(),
        unusedDependenciesParams.getExportedDeps(),
        unusedDependenciesParams.getUnusedDependenciesAction(),
        unusedDependenciesParams.getBuildozerPath(),
        unusedDependenciesParams.isOnlyPrintCommands(),
        unusedDependenciesParams.getKnownCellNames(),
        unusedDependenciesParams.getCellToPathMappings(),
        depFile,
        unusedDependenciesParams.isDoUltralightChecking());
  }
}
