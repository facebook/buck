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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.AbstractCxxSource;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;

public class AppleLibraryBuilder
    extends AbstractNodeBuilder<
        AppleLibraryDescriptionArg.Builder,
        AppleLibraryDescriptionArg,
        AppleLibraryDescription,
        BuildRule> {

  protected AppleLibraryBuilder(BuildTarget target) {
    super(FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION, target);
  }

  public static AppleLibraryBuilder createBuilder(BuildTarget target) {
    return new AppleLibraryBuilder(target);
  }

  public AppleLibraryBuilder setModular(boolean modular) {
    getArgForPopulating().setModular(modular);
    return this;
  }

  public AppleLibraryBuilder setConfigs(
      ImmutableSortedMap<String, ImmutableMap<String, String>> configs) {
    getArgForPopulating().setConfigs(configs);
    return this;
  }

  public AppleLibraryBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(StringWithMacrosUtils.fromStrings(compilerFlags));
    return this;
  }

  public AppleLibraryBuilder setPreprocessorFlags(ImmutableList<String> preprocessorFlags) {
    getArgForPopulating()
        .setPreprocessorFlags(
            RichStream.from(preprocessorFlags)
                .map(StringWithMacrosUtils::format)
                .toImmutableList());
    return this;
  }

  public AppleLibraryBuilder setLangPreprocessorFlags(
      ImmutableMap<AbstractCxxSource.Type, ImmutableList<String>> langPreprocessorFlags) {
    getArgForPopulating()
        .setLangPreprocessorFlags(
            Maps.transformValues(
                langPreprocessorFlags,
                f -> RichStream.from(f).map(StringWithMacrosUtils::format).toImmutableList()));
    return this;
  }

  public AppleLibraryBuilder setExportedPreprocessorFlags(
      ImmutableList<String> exportedPreprocessorFlags) {
    getArgForPopulating()
        .setExportedPreprocessorFlags(
            RichStream.from(exportedPreprocessorFlags)
                .map(StringWithMacrosUtils::format)
                .toImmutableList());
    return this;
  }

  public AppleLibraryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public AppleLibraryBuilder setExportedLinkerFlags(
      ImmutableList<StringWithMacros> exportedLinkerFlags) {
    getArgForPopulating().setExportedLinkerFlags(exportedLinkerFlags);
    return this;
  }

  public AppleLibraryBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public AppleLibraryBuilder setPlatformSrcs(
      PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
    getArgForPopulating().setPlatformSrcs(platformSrcs);
    return this;
  }

  public AppleLibraryBuilder setExtraXcodeSources(ImmutableList<SourcePath> extraXcodeSources) {
    getArgForPopulating().setExtraXcodeSources(extraXcodeSources);
    return this;
  }

  public AppleLibraryBuilder setExtraXcodeFiles(ImmutableList<SourcePath> extraXcodeFiles) {
    getArgForPopulating().setExtraXcodeFiles(extraXcodeFiles);
    return this;
  }

  public AppleLibraryBuilder setHeaders(SourceSortedSet headers) {
    getArgForPopulating().setHeaders(headers);
    return this;
  }

  public AppleLibraryBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    return setHeaders(SourceSortedSet.ofUnnamedSources(headers));
  }

  public AppleLibraryBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    return setHeaders(SourceSortedSet.ofNamedSources(headers));
  }

  public AppleLibraryBuilder setPlatformHeaders(
      PatternMatchedCollection<SourceSortedSet> platformHeaders) {
    getArgForPopulating().setPlatformHeaders(platformHeaders);
    return this;
  }

  public AppleLibraryBuilder setExportedPlatformHeaders(
      PatternMatchedCollection<SourceSortedSet> exportedPlatformHeaders) {
    getArgForPopulating().setExportedPlatformHeaders(exportedPlatformHeaders);
    return this;
  }

  public AppleLibraryBuilder setExportedHeaders(SourceSortedSet exportedHeaders) {
    getArgForPopulating().setExportedHeaders(exportedHeaders);
    return this;
  }

  public AppleLibraryBuilder setExportedHeaders(ImmutableSortedSet<SourcePath> exportedHeaders) {
    return setExportedHeaders(SourceSortedSet.ofUnnamedSources(exportedHeaders));
  }

  public AppleLibraryBuilder setExportedHeaders(
      ImmutableSortedMap<String, SourcePath> exportedHeaders) {
    return setExportedHeaders(SourceSortedSet.ofNamedSources(exportedHeaders));
  }

  public AppleLibraryBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public AppleLibraryBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public AppleLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public AppleLibraryBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> exportedDeps) {
    getArgForPopulating().setExportedDeps(exportedDeps);
    return this;
  }

  public AppleLibraryBuilder setHeaderPathPrefix(Optional<String> headerPathPrefix) {
    getArgForPopulating().setHeaderPathPrefix(headerPathPrefix);
    return this;
  }

  public AppleLibraryBuilder setPrefixHeader(Optional<SourcePath> prefixHeader) {
    getArgForPopulating().setPrefixHeader(prefixHeader);
    return this;
  }

  public AppleLibraryBuilder setPrecompiledHeader(Optional<SourcePath> precompiledHeader) {
    getArgForPopulating().setPrecompiledHeader(precompiledHeader);
    return this;
  }

  public AppleLibraryBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    getArgForPopulating().setTests(tests);
    return this;
  }

  public AppleLibraryBuilder setBridgingHeader(Optional<SourcePath> bridgingHeader) {
    getArgForPopulating().setBridgingHeader(bridgingHeader);
    return this;
  }

  public AppleLibraryBuilder setPreferredLinkage(NativeLinkable.Linkage linkage) {
    getArgForPopulating().setPreferredLinkage(Optional.of(linkage));
    return this;
  }

  public AppleLibraryBuilder setSwiftVersion(Optional<String> swiftVersion) {
    getArgForPopulating().setSwiftVersion(swiftVersion);
    return this;
  }

  public AppleLibraryBuilder setSwiftCompilerFlags(Iterable<? extends StringWithMacros> flags) {
    getArgForPopulating().setSwiftCompilerFlags(flags);
    return this;
  }

  public AppleLibraryBuilder setXcodePrivateHeadersSymlinks(boolean xcodePrivateHeadersSymlinks) {
    getArgForPopulating().setXcodePrivateHeadersSymlinks(Optional.of(xcodePrivateHeadersSymlinks));
    return this;
  }

  public AppleLibraryBuilder setXcodePublicHeadersSymlinks(boolean xcodePublicHeadersSymlinks) {
    getArgForPopulating().setXcodePublicHeadersSymlinks(Optional.of(xcodePublicHeadersSymlinks));
    return this;
  }

  public AppleLibraryBuilder setModuleName(String moduleName) {
    getArgForPopulating().setModuleName(moduleName);
    return this;
  }

  public AppleLibraryBuilder setLinkWhole(boolean linkWhole) {
    getArgForPopulating().setLinkWhole(Optional.of(linkWhole));
    return this;
  }
}
