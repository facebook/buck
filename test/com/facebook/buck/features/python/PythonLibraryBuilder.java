/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class PythonLibraryBuilder
    extends AbstractNodeBuilder<
        PythonLibraryDescriptionArg.Builder,
        PythonLibraryDescriptionArg,
        PythonLibraryDescription,
        PythonLibrary> {

  public PythonLibraryBuilder(
      BuildTarget target,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms) {
    super(
        new PythonLibraryDescription(
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

  public PythonLibraryBuilder(BuildTarget target) {
    this(target, PythonTestUtils.PYTHON_PLATFORMS, CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public static PythonLibraryBuilder createBuilder(BuildTarget target) {
    return new PythonLibraryBuilder(target);
  }

  public PythonLibraryBuilder setSrcs(SourceSortedSet srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public PythonLibraryBuilder setPlatformSrcs(
      PatternMatchedCollection<SourceSortedSet> platformSrcs) {
    getArgForPopulating().setPlatformSrcs(platformSrcs);
    return this;
  }

  public PythonLibraryBuilder setPlatformResources(
      PatternMatchedCollection<SourceSortedSet> platformResources) {
    getArgForPopulating().setPlatformResources(platformResources);
    return this;
  }

  public PythonLibraryBuilder setBaseModule(String baseModule) {
    getArgForPopulating().setBaseModule(Optional.of(baseModule));
    return this;
  }

  public PythonLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PythonLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> deps) {
    getArgForPopulating().setPlatformDeps(deps);
    return this;
  }

  public PythonLibraryBuilder setVersionedSrcs(
      VersionMatchedCollection<SourceSortedSet> versionedSrcs) {
    getArgForPopulating().setVersionedSrcs(Optional.of(versionedSrcs));
    return this;
  }

  public PythonLibraryBuilder setVersionedResources(
      VersionMatchedCollection<SourceSortedSet> versionedResources) {
    getArgForPopulating().setVersionedResources(Optional.of(versionedResources));
    return this;
  }

  public PythonLibraryBuilder setExcludeDepsFromMergedLinking(boolean excludeDepsFromOmnibus) {
    getArgForPopulating().setExcludeDepsFromMergedLinking(excludeDepsFromOmnibus);
    return this;
  }
}
