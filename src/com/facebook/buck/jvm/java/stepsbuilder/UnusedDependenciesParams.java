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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/** Parameters that used for {@link UnusedDependenciesFinder} creation. */
@BuckStyleValue
public abstract class UnusedDependenciesParams {

  public abstract ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> getDeps();

  public abstract ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath>
      getProvidedDeps();

  public abstract String getFullyQualifiedName();

  public abstract RelPath getDepFile();

  public abstract JavaBuckConfig.UnusedDependenciesAction getUnusedDependenciesAction();

  public abstract ImmutableList<String> getExportedDeps();

  public abstract Optional<String> getBuildozerPath();

  public abstract ImmutableSet<Optional<String>> getKnownCellNames();

  public abstract boolean isOnlyPrintCommands();

  public abstract boolean isDoUltralightChecking();

  /** Creates {@link UnusedDependenciesParams} */
  public static UnusedDependenciesParams of(
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> deps,
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> providedDeps,
      String fullyQualifiedName,
      RelPath depFile,
      JavaBuckConfig.UnusedDependenciesAction unusedDependenciesAction,
      ImmutableList<String> exportedDeps,
      Optional<String> buildozerPath,
      ImmutableSet<Optional<String>> knownCellNames,
      boolean onlyPrintCommands,
      boolean doUltralightChecking) {
    return ImmutableUnusedDependenciesParams.ofImpl(
        deps,
        providedDeps,
        fullyQualifiedName,
        depFile,
        unusedDependenciesAction,
        exportedDeps,
        buildozerPath,
        knownCellNames,
        onlyPrintCommands,
        doUltralightChecking);
  }

  /** Creates {@link UnusedDependenciesFinder} */
  public static UnusedDependenciesFinder createFinder(
      UnusedDependenciesParams unusedDependenciesParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings) {
    return UnusedDependenciesFinder.of(
        unusedDependenciesParams.getFullyQualifiedName(),
        unusedDependenciesParams.getDeps(),
        unusedDependenciesParams.getProvidedDeps(),
        unusedDependenciesParams.getExportedDeps(),
        unusedDependenciesParams.getUnusedDependenciesAction(),
        unusedDependenciesParams.getBuildozerPath(),
        unusedDependenciesParams.isOnlyPrintCommands(),
        unusedDependenciesParams.getKnownCellNames(),
        cellToPathMappings,
        unusedDependenciesParams.getDepFile(),
        unusedDependenciesParams.isDoUltralightChecking());
  }
}
