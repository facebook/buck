/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class CxxBinaryBuilder
    extends AbstractNodeBuilder<
        CxxBinaryDescriptionArg.Builder, CxxBinaryDescriptionArg, CxxBinaryDescription, CxxBinary> {

  private static CxxBinaryDescription createCxxBinaryDescription(
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxBuckConfig cxxBuckConfig) {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(defaultCxxPlatform, cxxPlatforms))
            .build();

    return new CxxBinaryDescription(
        toolchainProvider,
        new CxxBinaryImplicitFlavors(toolchainProvider, cxxBuckConfig),
        new CxxBinaryFactory(
            toolchainProvider,
            cxxBuckConfig,
            new InferBuckConfig(FakeBuckConfig.builder().build())),
        new CxxBinaryMetadataFactory(toolchainProvider),
        new CxxBinaryFlavored(toolchainProvider, cxxBuckConfig));
  }

  public CxxBinaryBuilder(
      BuildTarget target, CxxPlatform defaultCxxPlatform, FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        createCxxBinaryDescription(
            defaultCxxPlatform, cxxPlatforms, CxxPlatformUtils.DEFAULT_CONFIG),
        target);
  }

  public CxxBinaryBuilder(
      BuildTarget target,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxBuckConfig cxxBuckConfig) {
    super(createCxxBinaryDescription(defaultCxxPlatform, cxxPlatforms, cxxBuckConfig), target);
  }

  public CxxBinaryBuilder(BuildTarget target) {
    this(target, CxxPlatformUtils.DEFAULT_PLATFORM, CxxTestUtils.createDefaultPlatforms());
  }

  public CxxBinaryBuilder(BuildTarget target, CxxBuckConfig cxxBuckConfig) {
    this(
        target,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxTestUtils.createDefaultPlatforms(),
        cxxBuckConfig);
  }

  public CxxBinaryBuilder setDepQuery(Query depQuery) {
    getArgForPopulating().setDepsQuery(Optional.of(depQuery));
    return this;
  }

  public CxxBinaryBuilder setVersionUniverse(String versionUniverse) {
    getArgForPopulating().setVersionUniverse(Optional.of(versionUniverse));
    return this;
  }

  public CxxBinaryBuilder setDefaultPlatform(Flavor flavor) {
    getArgForPopulating().setDefaultPlatform(Optional.of(flavor));
    return this;
  }

  public CxxBinaryBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public CxxBinaryBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public CxxBinaryBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofNamedSources(headers));
    return this;
  }

  public CxxBinaryBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(StringWithMacrosUtils.fromStrings(compilerFlags));
    return this;
  }

  public CxxBinaryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public CxxBinaryBuilder setPlatformLinkerFlags(
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags) {
    getArgForPopulating().setPlatformLinkerFlags(platformLinkerFlags);
    return this;
  }

  public CxxBinaryBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public CxxBinaryBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public CxxBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }
}
