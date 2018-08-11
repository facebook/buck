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

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class CxxLibraryBuilder
    extends AbstractNodeBuilder<
        CxxLibraryDescriptionArg.Builder,
        CxxLibraryDescriptionArg,
        CxxLibraryDescription,
        BuildRule> {

  private static CxxLibraryDescription createCxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig, FlavorDomain<CxxPlatform> cxxPlatforms) {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(CxxPlatformUtils.build(cxxBuckConfig), cxxPlatforms))
            .build();
    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(
            toolchainProvider,
            cxxBuckConfig,
            new InferBuckConfig(FakeBuckConfig.builder().build()));
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(toolchainProvider);
    return new CxxLibraryDescription(
        cxxLibraryImplicitFlavors,
        new CxxLibraryFlavored(toolchainProvider, cxxBuckConfig),
        cxxLibraryFactory,
        cxxLibraryMetadataFactory);
  }

  public CxxLibraryBuilder(
      BuildTarget target, CxxBuckConfig cxxBuckConfig, FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(createCxxLibraryDescription(cxxBuckConfig, cxxPlatforms), target);
  }

  public CxxLibraryBuilder(BuildTarget target, CxxBuckConfig cxxBuckConfig) {
    this(target, cxxBuckConfig, CxxTestUtils.createDefaultPlatforms());
  }

  public CxxLibraryBuilder(BuildTarget target) {
    this(
        target,
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestUtils.createDefaultPlatforms());
  }

  public CxxLibraryBuilder setExportedHeaders(ImmutableSortedSet<SourcePath> headers) {
    getArgForPopulating().setExportedHeaders(SourceSortedSet.ofUnnamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setExportedHeaders(SourceSortedSet.ofNamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(SourceSortedSet headers) {
    getArgForPopulating().setExportedHeaders(headers);
    return this;
  }

  public CxxLibraryBuilder setExportedPreprocessorFlags(
      ImmutableList<String> exportedPreprocessorFlags) {
    getArgForPopulating()
        .setExportedPreprocessorFlags(
            RichStream.from(exportedPreprocessorFlags)
                .map(StringWithMacrosUtils::format)
                .toImmutableList());
    return this;
  }

  public CxxLibraryBuilder setExportedPlatformPreprocessorFlags(
      PatternMatchedCollection<ImmutableList<String>> exportedPlatformPreprocessorFlags) {
    getArgForPopulating()
        .setExportedPlatformPreprocessorFlags(
            exportedPlatformPreprocessorFlags.map(
                flags ->
                    RichStream.from(flags).map(StringWithMacrosUtils::format).toImmutableList()));
    return this;
  }

  public CxxLibraryBuilder setExportedLinkerFlags(
      ImmutableList<StringWithMacros> exportedLinkerFlags) {
    getArgForPopulating().setExportedLinkerFlags(exportedLinkerFlags);
    return this;
  }

  public CxxLibraryBuilder setExportedPlatformLinkerFlags(
      PatternMatchedCollection<ImmutableList<StringWithMacros>> exportedPlatformLinkerFlags) {
    getArgForPopulating().setExportedPlatformLinkerFlags(exportedPlatformLinkerFlags);
    return this;
  }

  public CxxLibraryBuilder setSoname(String soname) {
    getArgForPopulating().setSoname(Optional.of(soname));
    return this;
  }

  public CxxLibraryBuilder setStaticLibraryBasename(String staticLibraryBasename) {
    getArgForPopulating().setStaticLibraryBasename(Optional.of(staticLibraryBasename));
    return this;
  }

  public CxxLibraryBuilder setLinkWhole(boolean linkWhole) {
    getArgForPopulating().setLinkWhole(Optional.of(linkWhole));
    return this;
  }

  public CxxLibraryBuilder setForceStatic(boolean forceStatic) {
    getArgForPopulating().setForceStatic(Optional.of(forceStatic));
    return this;
  }

  public CxxLibraryBuilder setPreferredLinkage(NativeLinkable.Linkage linkage) {
    getArgForPopulating().setPreferredLinkage(Optional.of(linkage));
    return this;
  }

  public CxxLibraryBuilder setSupportedPlatformsRegex(Pattern regex) {
    getArgForPopulating().setSupportedPlatformsRegex(Optional.of(regex));
    return this;
  }

  public CxxLibraryBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> exportedDeps) {
    getArgForPopulating().setExportedDeps(exportedDeps);
    return this;
  }

  public CxxLibraryBuilder setXcodePrivateHeadersSymlinks(boolean xcodePrivateHeadersSymlinks) {
    getArgForPopulating().setXcodePrivateHeadersSymlinks(Optional.of(xcodePrivateHeadersSymlinks));
    return this;
  }

  public CxxLibraryBuilder setXcodePublicHeadersSymlinks(boolean xcodePublicHeadersSymlinks) {
    getArgForPopulating().setXcodePublicHeadersSymlinks(Optional.of(xcodePublicHeadersSymlinks));
    return this;
  }

  public CxxLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    getArgForPopulating().setPlatformDeps(platformDeps);
    return this;
  }

  public CxxLibraryBuilder setExportedPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> exportedPlatformDeps) {
    getArgForPopulating().setExportedPlatformDeps(exportedPlatformDeps);
    return this;
  }

  public CxxLibraryBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public CxxLibraryBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceSortedSet.ofUnnamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceSortedSet.ofNamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating()
        .setCompilerFlags(
            RichStream.from(compilerFlags).map(StringWithMacrosUtils::format).toImmutableList());
    return this;
  }

  public CxxLibraryBuilder setPreprocessorFlags(ImmutableList<String> preprocessorFlags) {
    getArgForPopulating()
        .setPreprocessorFlags(
            RichStream.from(preprocessorFlags)
                .map(StringWithMacrosUtils::format)
                .toImmutableList());
    return this;
  }

  public CxxLibraryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public CxxLibraryBuilder setPlatformCompilerFlags(
      PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags) {
    getArgForPopulating()
        .setPlatformCompilerFlags(
            platformCompilerFlags.map(
                flags ->
                    RichStream.from(flags).map(StringWithMacrosUtils::format).toImmutableList()));
    return this;
  }

  public CxxLibraryBuilder setPlatformPreprocessorFlags(
      PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags) {
    getArgForPopulating()
        .setPlatformPreprocessorFlags(
            platformPreprocessorFlags.map(
                flags ->
                    RichStream.from(flags).map(StringWithMacrosUtils::format).toImmutableList()));
    return this;
  }

  public CxxLibraryBuilder setPlatformLinkerFlags(
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags) {
    getArgForPopulating().setPlatformLinkerFlags(platformLinkerFlags);
    return this;
  }

  public CxxLibraryBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public CxxLibraryBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public CxxLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public CxxLibraryBuilder setHeaderNamespace(String namespace) {
    getArgForPopulating().setHeaderNamespace(Optional.of(namespace));
    return this;
  }
}
