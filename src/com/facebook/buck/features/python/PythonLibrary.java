/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.stream.Stream;

public class PythonLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements PythonPackagable, HasRuntimeDeps {

  private boolean excludeDepsFromOmnibus;

  PythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      boolean excludeDepsFromOmnibus) {
    super(buildTarget, projectFilesystem, params);
    this.excludeDepsFromOmnibus = excludeDepsFromOmnibus;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return graphBuilder
        .requireMetadata(
            getBuildTarget()
                .withAppendedFlavors(
                    PythonLibraryDescription.MetadataType.PACKAGE_DEPS.getFlavor(),
                    pythonPlatform.getFlavor(),
                    cxxPlatform.getFlavor()),
            Iterable.class)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return graphBuilder
        .requireMetadata(
            getBuildTarget()
                .withAppendedFlavors(
                    PythonLibraryDescription.MetadataType.PACKAGE_COMPONENTS.getFlavor(),
                    pythonPlatform.getFlavor(),
                    cxxPlatform.getFlavor()),
            PythonPackageComponents.class)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public boolean doesPythonPackageDisallowOmnibus() {
    return excludeDepsFromOmnibus;
  }
}
