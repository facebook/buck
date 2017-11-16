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

package com.facebook.buck.haskell;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.SystemToolProvider;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

public class HaskellBuckConfig {

  private static final Integer DEFAULT_MAJOR_VERSION = 7;
  private static final String SECTION_PREFIX = "haskell";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public HaskellBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  private Optional<ImmutableList<String>> getFlags(String section, String field) {
    Optional<String> value = delegate.getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  private ToolProvider getTool(String section, String configName, String systemName) {
    return delegate
        .getView(ToolConfig.class)
        .getToolProvider(section, configName)
        .orElseGet(
            () ->
                SystemToolProvider.builder()
                    .setExecutableFinder(finder)
                    .setSourcePathConverter(delegate::getPathSourcePath)
                    .setName(Paths.get(systemName))
                    .setEnvironment(delegate.getEnvironment())
                    .setSource(String.format(".buckconfig (%s.%s)", section, configName))
                    .build());
  }

  private HaskellPlatform getPlatform(String section, CxxPlatform cxxPlatform) {
    return HaskellPlatform.builder()
        .setHaskellVersion(
            HaskellVersion.of(
                delegate
                    .getInteger(section, "compiler_major_version")
                    .orElse(DEFAULT_MAJOR_VERSION)))
        .setCompiler(getTool(section, "compiler", "ghc"))
        .setCompilerFlags(getFlags(section, "compiler_flags").orElse(ImmutableList.of()))
        .setLinker(getTool(section, "linker", "ghc"))
        .setLinkerFlags(getFlags(section, "linker_flags").orElse(ImmutableList.of()))
        .setPackager(getTool(section, "packager", "ghc-pkg"))
        .setHaddock(getTool(section, "haddock", "haddock"))
        .setShouldCacheLinks(delegate.getBooleanValue(section, "cache_links", true))
        .setShouldUsedOldBinaryOutputLocation(
            delegate.getBoolean(section, "old_binary_output_location"))
        .setPackageNamePrefix(delegate.getValue(section, "package_name_prefix"))
        .setGhciScriptTemplate(() -> delegate.getRequiredPath(section, "ghci_script_template"))
        .setGhciIservScriptTemplate(
            () -> delegate.getRequiredPath(section, "ghci_iserv_script_template"))
        .setGhciBinutils(() -> delegate.getRequiredPath(section, "ghci_binutils_path"))
        .setGhciGhc(() -> delegate.getRequiredPath(section, "ghci_ghc_path"))
        .setGhciIServ(() -> delegate.getRequiredPath(section, "ghci_iserv_path"))
        .setGhciIServProf(() -> delegate.getRequiredPath(section, "ghci_iserv_prof_path"))
        .setGhciLib(() -> delegate.getRequiredPath(section, "ghci_lib_path"))
        .setGhciCxx(() -> delegate.getRequiredPath(section, "ghci_cxx_path"))
        .setGhciCc(() -> delegate.getRequiredPath(section, "ghci_cc_path"))
        .setGhciCpp(() -> delegate.getRequiredPath(section, "ghci_cpp_path"))
        .setLinkStyleForStubHeader(
            delegate.getEnum(section, "link_style_for_stub_header", Linker.LinkableDepType.class))
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  public ImmutableList<HaskellPlatform> getPlatforms(Iterable<CxxPlatform> cxxPlatforms) {
    return RichStream.from(cxxPlatforms)
        .map(
            cxxPlatform ->
                // We special case the "default" C/C++ platform to just use the "haskell" section.
                cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                    ? getPlatform(SECTION_PREFIX, cxxPlatform)
                    : getPlatform(
                        String.format("%s#%s", SECTION_PREFIX, cxxPlatform.getFlavor()),
                        cxxPlatform))
        .toImmutableList();
  }
}
