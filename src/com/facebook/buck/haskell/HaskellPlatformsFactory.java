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

package com.facebook.buck.haskell;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;

public class HaskellPlatformsFactory {

  private final HaskellBuckConfig haskellBuckConfig;

  public HaskellPlatformsFactory(BuckConfig buckConfig, ExecutableFinder executableFinder) {
    this.haskellBuckConfig = new HaskellBuckConfig(buckConfig, executableFinder);
  }

  private HaskellPlatform getPlatform(String section, CxxPlatform cxxPlatform) {
    return HaskellPlatform.builder()
        .setHaskellVersion(HaskellVersion.of(haskellBuckConfig.getCompilerMajorVersion(section)))
        .setCompiler(haskellBuckConfig.getCompiler(section))
        .setCompilerFlags(haskellBuckConfig.getCompilerFlags(section).orElse(ImmutableList.of()))
        .setLinker(haskellBuckConfig.getLinker(section))
        .setLinkerFlags(haskellBuckConfig.getLinkerFlags(section).orElse(ImmutableList.of()))
        .setPackager(haskellBuckConfig.getPackager(section))
        .setHaddock(haskellBuckConfig.getHaddock(section))
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
}
