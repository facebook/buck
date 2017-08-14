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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.SystemToolProvider;
import com.facebook.buck.rules.ToolProvider;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

public class HaskellBuckConfig {

  private static final Integer DEFAULT_MAJOR_VERSION = 7;
  private static final String SECTION = "haskell";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public HaskellBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  private Optional<ImmutableList<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(SECTION, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  private ToolProvider getTool(String configName, String systemName) {
    return delegate
        .getToolProvider(SECTION, configName)
        .orElseGet(
            () ->
                SystemToolProvider.builder()
                    .setExecutableFinder(finder)
                    .setName(Paths.get(systemName))
                    .setEnvironment(delegate.getEnvironment())
                    .setSource(String.format(".buckconfig (%s.%s)", SECTION, configName))
                    .build());
  }

  public HaskellPlatform getPlatform(CxxPlatform cxxPlatform) {
    return HaskellPlatform.builder()
        .setHaskellVersion(
            HaskellVersion.of(
                delegate
                    .getInteger(SECTION, "compiler_major_version")
                    .orElse(DEFAULT_MAJOR_VERSION)))
        .setCompiler(getTool("compiler", "ghc"))
        .setCompilerFlags(getFlags("compiler_flags").orElse(ImmutableList.of()))
        .setLinker(getTool("linker", "ghc"))
        .setLinkerFlags(getFlags("linker_flags").orElse(ImmutableList.of()))
        .setPackager(getTool("packager", "ghc-pkg"))
        .setShouldCacheLinks(delegate.getBooleanValue(SECTION, "cache_links", true))
        .setShouldUsedOldBinaryOutputLocation(
            delegate.getBoolean(SECTION, "old_binary_output_location"))
        .setPackageNamePrefix(delegate.getValue(SECTION, "package_name_prefix"))
        .setGhciScriptTemplate(() -> delegate.getRequiredPath(SECTION, "ghci_script_template"))
        .setGhciBinutils(() -> delegate.getRequiredPath(SECTION, "ghci_binutils_path"))
        .setGhciGhc(() -> delegate.getRequiredPath(SECTION, "ghci_ghc_path"))
        .setGhciLib(() -> delegate.getRequiredPath(SECTION, "ghci_lib_path"))
        .setGhciCxx(() -> delegate.getRequiredPath(SECTION, "ghci_cxx_path"))
        .setGhciCc(() -> delegate.getRequiredPath(SECTION, "ghci_cc_path"))
        .setGhciCpp(() -> delegate.getRequiredPath(SECTION, "ghci_cpp_path"))
        .setCxxPlatform(cxxPlatform)
        .build();
  }
}
