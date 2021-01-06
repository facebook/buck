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
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltPythonLibraryBuilder
    extends AbstractNodeBuilder<
        PrebuiltPythonLibraryDescriptionArg.Builder,
        PrebuiltPythonLibraryDescriptionArg,
        PrebuiltPythonLibraryDescription,
        PrebuiltPythonLibrary> {

  PrebuiltPythonLibraryBuilder(
      BuildTarget target,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms) {
    super(
        new PrebuiltPythonLibraryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    PythonPlatformsProvider.DEFAULT_NAME,
                    PythonPlatformsProvider.of(pythonPlatforms))
                .withToolchain(
                    CxxPlatformsProvider.DEFAULT_NAME,
                    CxxPlatformsProvider.of(
                        CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM, cxxPlatforms))
                .build()),
        target);
  }

  public static PrebuiltPythonLibraryBuilder createBuilder(BuildTarget target) {
    return new PrebuiltPythonLibraryBuilder(
        target, PythonTestUtils.PYTHON_PLATFORMS, CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public PrebuiltPythonLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PrebuiltPythonLibraryBuilder setBinarySrc(SourcePath binarySrc) {
    getArgForPopulating().setBinarySrc(binarySrc);
    return this;
  }

  public PrebuiltPythonLibraryBuilder setCompile(boolean compile) {
    getArgForPopulating().setCompile(compile);
    return this;
  }

  public PrebuiltPythonLibraryBuilder setExcludeDepsFromMergedLinking(
      boolean excludeDepsFromOmnibus) {
    getArgForPopulating().setExcludeDepsFromMergedLinking(excludeDepsFromOmnibus);
    return this;
  }
}
