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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class PythonLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements PythonPackagable, HasRuntimeDeps {

  private Optional<Boolean> zipSafe;
  private boolean excludeDepsFromOmnibus;

  PythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<Boolean> zipSafe,
      boolean excludeDepsFromOmnibus) {
    super(buildTarget, projectFilesystem, params);
    this.zipSafe = zipSafe;
    this.excludeDepsFromOmnibus = excludeDepsFromOmnibus;
  }

  private <T> T getMetadata(
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      PythonLibraryDescription.MetadataType type,
      Class<T> clazz) {
    return graphBuilder
        .requireMetadata(
            getBuildTarget()
                .withAppendedFlavors(
                    type.getFlavor(), pythonPlatform.getFlavor(), cxxPlatform.getFlavor()),
            clazz)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getMetadata(
        pythonPlatform,
        cxxPlatform,
        graphBuilder,
        PythonLibraryDescription.MetadataType.PACKAGE_DEPS,
        ImmutableSortedSet.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<PythonMappedComponents> getPythonModules(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getMetadata(
        pythonPlatform,
        cxxPlatform,
        graphBuilder,
        PythonLibraryDescription.MetadataType.MODULES,
        Optional.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<PythonMappedComponents> getPythonResources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getMetadata(
        pythonPlatform,
        cxxPlatform,
        graphBuilder,
        PythonLibraryDescription.MetadataType.RESOURCES,
        Optional.class);
  }

  @SuppressWarnings("unchecked")
  private Optional<PythonMappedComponents> getPythonSources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getMetadata(
        pythonPlatform,
        cxxPlatform,
        graphBuilder,
        PythonLibraryDescription.MetadataType.SOURCES,
        Optional.class);
  }

  @Override
  public Optional<PythonComponents> getPythonBytecode(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonSources(pythonPlatform, cxxPlatform, graphBuilder)
        .map(
            sources -> {
              PythonCompileRule compileRule =
                  (PythonCompileRule)
                      graphBuilder.requireRule(
                          getBuildTarget()
                              .withAppendedFlavors(
                                  pythonPlatform.getFlavor(),
                                  cxxPlatform.getFlavor(),
                                  PythonLibraryDescription.LibraryType.COMPILE.getFlavor()));
              return compileRule.getCompiledSources();
            });
  }

  @Override
  public Optional<Boolean> isPythonZipSafe() {
    return zipSafe;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public boolean doesPythonPackageDisallowOmnibus(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (excludeDepsFromOmnibus) {
      return true;
    }

    // In some cases, Python library rules package prebuilt native extensions, in which case, we
    // can't support library merging (since we can't re-link these extensions).
    for (Path module :
        getPythonModules(pythonPlatform, cxxPlatform, graphBuilder)
            .map(PythonMappedComponents::getComponents)
            .orElse(ImmutableSortedMap.of())
            .keySet()) {
      if (MorePaths.getFileExtension(module).equals(PythonUtil.NATIVE_EXTENSION_EXT)) {
        return true;
      }
    }

    return false;
  }
}
