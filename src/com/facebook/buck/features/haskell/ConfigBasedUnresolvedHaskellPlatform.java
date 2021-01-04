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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

/** An {@link UnresolvedHaskellPlatform} based on .buckconfig values. */
public class ConfigBasedUnresolvedHaskellPlatform implements UnresolvedHaskellPlatform {

  private final String section;
  private final HaskellBuckConfig haskellBuckConfig;
  private final UnresolvedCxxPlatform unresolvedCxxPlatform;
  private final BuckConfig buckConfig;
  private final ExecutableFinder executableFinder;

  private final ToolProvider compiler;
  private final ToolProvider linker;
  private final ToolProvider packager;
  private final ToolProvider haddock;

  ConfigBasedUnresolvedHaskellPlatform(
      String section,
      HaskellBuckConfig haskellBuckConfig,
      UnresolvedCxxPlatform unresolvedCxxPlatform,
      BuckConfig buckConfig,
      ExecutableFinder executableFinder) {
    this.section = section;
    this.haskellBuckConfig = haskellBuckConfig;
    this.unresolvedCxxPlatform = unresolvedCxxPlatform;
    this.buckConfig = buckConfig;
    this.executableFinder = executableFinder;

    this.compiler = getCompiler(section);
    this.linker = getLinker(section);
    this.packager = getPackager(section);
    this.haddock = getHaddock(section);
  }

  @Override
  public HaskellPlatform resolve(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    CxxPlatform cxxPlatform = unresolvedCxxPlatform.resolve(resolver, targetConfiguration);
    return HaskellPlatform.builder()
        .setHaskellVersion(
            ImmutableHaskellVersion.ofImpl(haskellBuckConfig.getCompilerMajorVersion(section)))
        .setCompiler(compiler)
        .setCompilerFlags(haskellBuckConfig.getCompilerFlags(section).orElse(ImmutableList.of()))
        .setLinker(linker)
        .setLinkerFlags(haskellBuckConfig.getLinkerFlags(section).orElse(ImmutableList.of()))
        .setPackager(packager)
        .setHaddock(haddock)
        .setShouldCacheLinks(haskellBuckConfig.getShouldCacheLinks(section))
        .setShouldUseArgsfile(haskellBuckConfig.getShouldUseArgsfile(section))
        .setShouldUsedOldBinaryOutputLocation(
            haskellBuckConfig.getShouldUsedOldBinaryOutputLocation(section))
        .setSupportExposePackage(haskellBuckConfig.getSupportExposePackage(section))
        .setArchiveContents(
            haskellBuckConfig.getArchiveContents(section).orElse(cxxPlatform.getArchiveContents()))
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
        .setGhciPackager(haskellBuckConfig.getGhciPackager(section))
        .setIdeScriptTemplate(haskellBuckConfig.getIdeScriptTemplate(section))
        .setLinkStyleForStubHeader(haskellBuckConfig.getLinkStyleForStubHeader(section))
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  private ToolProvider getTool(
      Optional<ToolProvider> toolProviderFromConfig, String source, String systemName) {
    return toolProviderFromConfig.orElseGet(
        () ->
            SystemToolProvider.of(
                executableFinder,
                buckConfig::getPathSourcePath,
                Paths.get(systemName),
                buckConfig.getEnvironment(),
                Optional.of(source)));
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

  @Override
  public Flavor getFlavor() {
    return unresolvedCxxPlatform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.<BuildTarget>builder()
        .addAll(compiler.getParseTimeDeps(targetConfiguration))
        .addAll(linker.getParseTimeDeps(targetConfiguration))
        .addAll(packager.getParseTimeDeps(targetConfiguration))
        .addAll(haddock.getParseTimeDeps(targetConfiguration))
        .addAll(unresolvedCxxPlatform.getParseTimeDeps(targetConfiguration))
        .build();
  }
}
