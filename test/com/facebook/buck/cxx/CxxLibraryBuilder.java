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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class CxxLibraryBuilder
    extends AbstractNodeBuilder<
        CxxLibraryDescriptionArg.Builder, CxxLibraryDescriptionArg, CxxLibraryDescription,
        BuildRule> {

  public CxxLibraryBuilder(
      BuildTarget target, CxxBuckConfig cxxBuckConfig, FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new CxxLibraryDescription(
            cxxBuckConfig,
            CxxPlatformUtils.build(cxxBuckConfig),
            new InferBuckConfig(FakeBuckConfig.builder().build()),
            cxxPlatforms),
        target);
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
    getArgForPopulating().setExportedHeaders(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setExportedHeaders(SourceList.ofNamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setExportedHeaders(SourceList headers) {
    getArgForPopulating().setExportedHeaders(headers);
    return this;
  }

  public CxxLibraryBuilder setExportedPreprocessorFlags(
      ImmutableList<String> exportedPreprocessorFlags) {
    getArgForPopulating().setExportedPreprocessorFlags(exportedPreprocessorFlags);
    return this;
  }

  public CxxLibraryBuilder setExportedPlatformPreprocessorFlags(
      PatternMatchedCollection<ImmutableList<String>> exportedPlatformPreprocessorFlags) {
    getArgForPopulating().setExportedPlatformPreprocessorFlags(exportedPlatformPreprocessorFlags);
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
    getArgForPopulating().setHeaders(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofNamedSources(headers));
    return this;
  }

  public CxxLibraryBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(compilerFlags);
    return this;
  }

  public CxxLibraryBuilder setPreprocessorFlags(ImmutableList<String> preprocessorFlags) {
    getArgForPopulating().setPreprocessorFlags(preprocessorFlags);
    return this;
  }

  public CxxLibraryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public CxxLibraryBuilder setPlatformCompilerFlags(
      PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags) {
    getArgForPopulating().setPlatformCompilerFlags(platformCompilerFlags);
    return this;
  }

  public CxxLibraryBuilder setPlatformPreprocessorFlags(
      PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags) {
    getArgForPopulating().setPlatformPreprocessorFlags(platformPreprocessorFlags);
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
