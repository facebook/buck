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
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class PrebuiltPythonLibrary extends NoopBuildRule implements PythonPackagable {

  private final ImmutableSortedSet<BuildTarget> deps;
  private final boolean excludeDepsFromOmnibus;
  private final boolean compile;

  public PrebuiltPythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildTarget> deps,
      boolean excludeDepsFromOmnibus,
      boolean compile) {
    super(buildTarget, projectFilesystem);
    this.deps = deps;
    this.excludeDepsFromOmnibus = excludeDepsFromOmnibus;
    this.compile = compile;
  }

  private SourcePath getExtractedSources(ActionGraphBuilder graphBuilder) {
    return graphBuilder
        .requireRule(
            getBuildTarget()
                .withAppendedFlavors(
                    PrebuiltPythonLibraryDescription.LibraryType.EXTRACT.getFlavor()))
        .getSourcePathToOutput();
  }

  @Override
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return graphBuilder.getAllRules(deps);
  }

  @Override
  public Optional<PythonComponents> getPythonModules(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofModules(getExtractedSources(graphBuilder)));
  }

  @Override
  public Optional<PythonComponents> getPythonResources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofResources(getExtractedSources(graphBuilder)));
  }

  private Optional<PythonComponents> getPythonSources(ActionGraphBuilder graphBuilder) {
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofSources(getExtractedSources(graphBuilder)));
  }

  @Override
  public Optional<PythonComponents> getPythonBytecode(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!compile) {
      return Optional.empty();
    }
    return getPythonSources(graphBuilder)
        .map(
            sources -> {
              PythonCompileRule compileRule =
                  (PythonCompileRule)
                      graphBuilder.requireRule(
                          getBuildTarget()
                              .withAppendedFlavors(
                                  pythonPlatform.getFlavor(),
                                  cxxPlatform.getFlavor(),
                                  PrebuiltPythonLibraryDescription.LibraryType.COMPILE
                                      .getFlavor()));
              return compileRule.getCompiledSources();
            });
  }

  @Override
  public boolean doesPythonPackageDisallowOmnibus(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return excludeDepsFromOmnibus;
  }
}
