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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

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
        .setHaskellVersion(HaskellVersion.of(getCompilerMajorVersion(section)))
        .setCompiler(getCompiler(section))
        .setCompilerFlags(getCompilerFlags(section).orElse(ImmutableList.of()))
        .setLinker(getLinker(section))
        .setLinkerFlags(getLinkerFlags(section).orElse(ImmutableList.of()))
        .setPackager(getPackager(section))
        .setHaddock(getHaddock(section))
        .setShouldCacheLinks(getShouldCacheLinks(section))
        .setShouldUsedOldBinaryOutputLocation(getShouldUsedOldBinaryOutputLocation(section))
        .setPackageNamePrefix(getPackageNamePrefix(section))
        .setGhciScriptTemplate(getGhciScriptTemplate(section))
        .setGhciIservScriptTemplate(getGhciIservScriptTemplate(section))
        .setGhciBinutils(getGhciBinutils(section))
        .setGhciGhc(getGhciGhc(section))
        .setGhciIServ(getGhciIServ(section))
        .setGhciIServProf(getGhciIServProf(section))
        .setGhciLib(getGhciLib(section))
        .setGhciCxx(getGhciCxx(section))
        .setGhciCc(getGhciCc(section))
        .setGhciCpp(getGhciCpp(section))
        .setLinkStyleForStubHeader(getLinkStyleForStubHeader(section))
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  public ImmutableList<HaskellPlatform> getPlatforms(Iterable<CxxPlatform> cxxPlatforms) {
    return RichStream.from(cxxPlatforms)
        .map(
            cxxPlatform ->
                // We special case the "default" C/C++ platform to just use the "haskell" section.
                cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                    ? getPlatform(getDefaultSection(), cxxPlatform)
                    : getPlatform(getSectionForPlatform(cxxPlatform), cxxPlatform))
        .toImmutableList();
  }

  public String getDefaultSection() {
    return SECTION_PREFIX;
  }

  public String getSectionForPlatform(CxxPlatform cxxPlatform) {
    return String.format("%s#%s", SECTION_PREFIX, cxxPlatform.getFlavor());
  }

  public Integer getCompilerMajorVersion(String section) {
    return delegate.getInteger(section, "compiler_major_version").orElse(DEFAULT_MAJOR_VERSION);
  }

  public ToolProvider getCompiler(String section) {
    return getTool(section, "compiler", "ghc");
  }

  public ToolProvider getLinker(String section) {
    return getTool(section, "linker", "ghc");
  }

  public ToolProvider getPackager(String section) {
    return getTool(section, "packager", "ghc-pkg");
  }

  public ToolProvider getHaddock(String section) {
    return getTool(section, "haddock", "haddock");
  }

  public Optional<ImmutableList<String>> getCompilerFlags(String section) {
    return getFlags(section, "compiler_flags");
  }

  public Optional<ImmutableList<String>> getLinkerFlags(String section) {
    return getFlags(section, "linker_flags");
  }

  public boolean getShouldCacheLinks(String section) {
    return delegate.getBooleanValue(section, "cache_links", true);
  }

  public Optional<Boolean> getShouldUsedOldBinaryOutputLocation(String section) {
    return delegate.getBoolean(section, "old_binary_output_location");
  }

  public Optional<String> getPackageNamePrefix(String section) {
    return delegate.getValue(section, "package_name_prefix");
  }

  public Supplier<Path> getGhciScriptTemplate(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_script_template");
  }

  public Supplier<Path> getGhciIservScriptTemplate(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_iserv_script_template");
  }

  public Supplier<Path> getGhciBinutils(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_binutils_path");
  }

  public Supplier<Path> getGhciGhc(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_ghc_path");
  }

  public Supplier<Path> getGhciIServ(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_iserv_path");
  }

  public Supplier<Path> getGhciIServProf(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_iserv_prof_path");
  }

  public Supplier<Path> getGhciLib(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_lib_path");
  }

  public Supplier<Path> getGhciCxx(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_cxx_path");
  }

  public Supplier<Path> getGhciCc(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_cc_path");
  }

  public Supplier<Path> getGhciCpp(String section) {
    return () -> delegate.getRequiredPath(section, "ghci_cpp_path");
  }

  public Optional<? extends Linker.LinkableDepType> getLinkStyleForStubHeader(String section) {
    return delegate.getEnum(section, "link_style_for_stub_header", Linker.LinkableDepType.class);
  }
}
