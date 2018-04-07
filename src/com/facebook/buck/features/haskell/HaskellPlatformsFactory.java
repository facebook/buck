/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.SystemToolProvider;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

public class HaskellPlatformsFactory {

  private final BuckConfig buckConfig;
  private final HaskellBuckConfig haskellBuckConfig;
  private final ExecutableFinder executableFinder;

  public HaskellPlatformsFactory(BuckConfig buckConfig, ExecutableFinder executableFinder) {
    this.buckConfig = buckConfig;
    this.haskellBuckConfig = new HaskellBuckConfig(buckConfig);
    this.executableFinder = executableFinder;
  }

  private HaskellPlatform getPlatform(String section, CxxPlatform cxxPlatform) {
    return HaskellPlatform.builder()
        .setHaskellVersion(HaskellVersion.of(haskellBuckConfig.getCompilerMajorVersion(section)))
        .setCompiler(getCompiler(section))
        .setCompilerFlags(haskellBuckConfig.getCompilerFlags(section).orElse(ImmutableList.of()))
        .setLinker(getLinker(section))
        .setLinkerFlags(haskellBuckConfig.getLinkerFlags(section).orElse(ImmutableList.of()))
        .setPackager(getPackager(section))
        .setHaddock(getHaddock(section))
        .setShouldCacheLinks(haskellBuckConfig.getShouldCacheLinks(section))
        .setShouldUsedOldBinaryOutputLocation(
            haskellBuckConfig.getShouldUsedOldBinaryOutputLocation(section))
        .setPackageNamePrefix(haskellBuckConfig.getPackageNamePrefix(section))
        .setGhciScriptTemplate(haskellBuckConfig.getGhciScriptTemplate(section))
        .setGhciIservScriptTemplate(haskellBuckConfig.getGhciIservScriptTemplate(section))
        .setGhciBinutils(haskellBuckConfig.getGhciBinutils(section))
        .setGhciGhc(haskellBuckConfig.getGhciGhc(section))
        .setGhciIServ(haskellBuckConfig.getGhciIServ(section))
        .setGhciIServProf(haskellBuckConfig.getGhciIServProf(section))
        .setGhciLib(haskellBuckConfig.getGhciLib(section))
        .setGhciCxx(haskellBuckConfig.getGhciCxx(section))
        .setGhciCc(haskellBuckConfig.getGhciCc(section))
        .setGhciCpp(haskellBuckConfig.getGhciCpp(section))
        .setLinkStyleForStubHeader(haskellBuckConfig.getLinkStyleForStubHeader(section))
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  public ImmutableList<HaskellPlatform> getPlatforms(Iterable<CxxPlatform> cxxPlatforms) {
    return RichStream.from(cxxPlatforms)
        .map(
            cxxPlatform ->
                // We special case the "default" C/C++ platform to just use the "haskell" section.
                cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                    ? getPlatform(haskellBuckConfig.getDefaultSection(), cxxPlatform)
                    : getPlatform(
                        haskellBuckConfig.getSectionForPlatform(cxxPlatform), cxxPlatform))
        .toImmutableList();
  }

  private ToolProvider getTool(
      Optional<ToolProvider> toolProviderFromConfig, String source, String systemName) {
    return toolProviderFromConfig.orElseGet(
        () ->
            SystemToolProvider.builder()
                .setExecutableFinder(executableFinder)
                .setSourcePathConverter(buckConfig::getPathSourcePath)
                .setName(Paths.get(systemName))
                .setEnvironment(buckConfig.getEnvironment())
                .setSource(source)
                .build());
  }

  private ToolProvider getCompiler(String section) {
    return getTool(
        haskellBuckConfig.getCompiler(section),
        haskellBuckConfig.getCompilerSource(section),
        "ghc");
  }

  private ToolProvider getLinker(String section) {
    return getTool(
        haskellBuckConfig.getLinker(section), haskellBuckConfig.getLinkerSource(section), "ghc");
  }

  private ToolProvider getPackager(String section) {
    return getTool(
        haskellBuckConfig.getPackager(section),
        haskellBuckConfig.getPackagerSource(section),
        "ghc-pkg");
  }

  private ToolProvider getHaddock(String section) {
    return getTool(
        haskellBuckConfig.getHaddock(section),
        haskellBuckConfig.getHaddockSource(section),
        "haddock");
  }
}
