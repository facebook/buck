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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class HaskellBuckConfig {

  private static final Integer DEFAULT_MAJOR_VERSION = 7;
  private static final String SECTION_PREFIX = "haskell";

  private final BuckConfig delegate;

  public HaskellBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
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

  private Optional<ToolProvider> getToolProvider(String section, String configName) {
    return delegate.getView(ToolConfig.class).getToolProvider(section, configName);
  }

  private String getToolSource(String section, String configName) {
    return String.format(".buckconfig (%s.%s)", section, configName);
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

  public Optional<ToolProvider> getCompiler(String section) {
    return getToolProvider(section, "compiler");
  }

  public String getCompilerSource(String section) {
    return getToolSource(section, "compiler");
  }

  public Optional<ToolProvider> getLinker(String section) {
    return getToolProvider(section, "linker");
  }

  public String getLinkerSource(String section) {
    return getToolSource(section, "linker");
  }

  public Optional<ToolProvider> getPackager(String section) {
    return getToolProvider(section, "packager");
  }

  public String getPackagerSource(String section) {
    return getToolSource(section, "packager");
  }

  public Optional<ToolProvider> getHaddock(String section) {
    return getToolProvider(section, "haddock");
  }

  public String getHaddockSource(String section) {
    return getToolSource(section, "haddock");
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
