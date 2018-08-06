/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.toolchain.toolprovider.impl.ErrorToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;

public class LuaBuckConfig {

  private static final String SECTION_PREFIX = "lua";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public LuaBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  private LuaPlatform getPlatform(String section, CxxPlatform cxxPlatform) {
    return LuaPlatform.builder()
        .setLua(
            delegate
                .getView(ToolConfig.class)
                .getToolProvider(section, "lua")
                .orElseGet(
                    () ->
                        SystemToolProvider.builder()
                            .setExecutableFinder(finder)
                            .setSourcePathConverter(delegate::getPathSourcePath)
                            .setName(Paths.get("lua"))
                            .setEnvironment(delegate.getEnvironment())
                            .build()))
        .setLuaCxxLibraryTarget(delegate.getBuildTarget(section, "cxx_library"))
        .setStarterType(
            delegate.getEnum(section, "starter_type", LuaBinaryDescription.StarterType.class))
        .setExtension(delegate.getValue(section, "extension").orElse(".lex"))
        .setNativeStarterLibrary(delegate.getBuildTarget(section, "native_starter_library"))
        .setPackageStyle(
            delegate
                .getEnum(section, "package_style", LuaPlatform.PackageStyle.class)
                .orElse(LuaPlatform.PackageStyle.INPLACE))
        .setPackager(
            delegate
                .getView(ToolConfig.class)
                .getToolProvider(section, "packager")
                .orElseGet(
                    () -> ErrorToolProvider.from("no packager set in '%s.packager'", section)))
        .setShouldCacheBinaries(delegate.getBooleanValue(section, "cache_binaries", true))
        .setNativeLinkStrategy(
            delegate
                .getEnum(section, "native_link_strategy", NativeLinkStrategy.class)
                .orElse(NativeLinkStrategy.SEPARATE))
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  /**
   * @return for each passed in {@link CxxPlatform}, build and wrap it in a {@link LuaPlatform}
   *     defined in the `lua#<cxx-platform-flavor>` config section.
   */
  public ImmutableList<LuaPlatform> getPlatforms(Iterable<CxxPlatform> cxxPlatforms) {
    return RichStream.from(cxxPlatforms)
        .map(
            cxxPlatform ->
                // We special case the "default" C/C++ platform to just use the "lua" section,
                // otherwise we load the `LuaPlatform` from the `lua#<cxx-platform-flavor>` section.
                cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                    ? getPlatform(SECTION_PREFIX, cxxPlatform)
                    : getPlatform(
                        String.format("%s#%s", SECTION_PREFIX, cxxPlatform.getFlavor()),
                        cxxPlatform))
        .toImmutableList();
  }
}
