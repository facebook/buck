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

package com.facebook.buck.lua;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.ErrorToolProvider;
import com.facebook.buck.rules.SystemToolProvider;
import java.nio.file.Paths;

public class LuaBuckConfig {

  private static final String SECTION = "lua";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public LuaBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  public LuaPlatform getPlatform() {
    return LuaPlatform.builder()
        .setLua(
            delegate
                .getToolProvider(SECTION, "lua")
                .orElseGet(
                    () ->
                        SystemToolProvider.builder()
                            .setExecutableFinder(finder)
                            .setName(Paths.get("lua"))
                            .setEnvironment(delegate.getEnvironment())
                            .build()))
        .setLuaCxxLibraryTarget(delegate.getBuildTarget(SECTION, "cxx_library"))
        .setStarterType(
            delegate.getEnum(SECTION, "starter_type", LuaBinaryDescription.StarterType.class))
        .setExtension(delegate.getValue(SECTION, "extension").orElse(".lex"))
        .setNativeStarterLibrary(delegate.getBuildTarget(SECTION, "native_starter_library"))
        .setPackageStyle(
            delegate
                .getEnum(SECTION, "package_style", LuaPlatform.PackageStyle.class)
                .orElse(LuaPlatform.PackageStyle.INPLACE))
        .setPackager(
            delegate
                .getToolProvider(SECTION, "packager")
                .orElseGet(
                    () -> ErrorToolProvider.from("no packager set in '%s.packager'", SECTION)))
        .setShouldCacheBinaries(delegate.getBooleanValue(SECTION, "cache_binaries", true))
        .setNativeLinkStrategy(
            delegate
                .getEnum(SECTION, "native_link_strategy", NativeLinkStrategy.class)
                .orElse(NativeLinkStrategy.SEPARATE))
        .build();
  }
}
