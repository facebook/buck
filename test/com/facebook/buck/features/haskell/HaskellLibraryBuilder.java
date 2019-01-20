/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class HaskellLibraryBuilder
    extends AbstractNodeBuilder<
        HaskellLibraryDescriptionArg.Builder,
        HaskellLibraryDescriptionArg,
        HaskellLibraryDescription,
        HaskellLibrary> {

  public HaskellLibraryBuilder(
      BuildTarget target,
      HaskellPlatform defaultPlatform,
      FlavorDomain<HaskellPlatform> platforms,
      CxxBuckConfig cxxBuckConfig) {
    super(
        new HaskellLibraryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    HaskellPlatformsProvider.DEFAULT_NAME,
                    HaskellPlatformsProvider.of(defaultPlatform, platforms))
                .build(),
            cxxBuckConfig),
        target);
  }

  public HaskellLibraryBuilder(BuildTarget target) {
    this(
        target,
        HaskellTestUtils.DEFAULT_PLATFORM,
        HaskellTestUtils.DEFAULT_PLATFORMS,
        CxxPlatformUtils.DEFAULT_CONFIG);
  }

  public HaskellLibraryBuilder setSrcs(SourceSortedSet srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public HaskellLibraryBuilder setCompilerFlags(ImmutableList<String> flags) {
    getArgForPopulating().setCompilerFlags(flags);
    return this;
  }

  public HaskellLibraryBuilder setLinkWhole(boolean linkWhole) {
    getArgForPopulating().setLinkWhole(linkWhole);
    return this;
  }

  public HaskellLibraryBuilder setPreferredLinkage(NativeLinkable.Linkage preferredLinkage) {
    getArgForPopulating().setPreferredLinkage(preferredLinkage);
    return this;
  }

  public HaskellLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public HaskellLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    getArgForPopulating().setPlatformDeps(platformDeps);
    return this;
  }

  public HaskellLibraryBuilder setPlatform(Flavor platform) {
    getArgForPopulating().setPlatform(platform);
    return this;
  }
}
