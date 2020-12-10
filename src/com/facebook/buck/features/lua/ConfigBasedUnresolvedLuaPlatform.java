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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ErrorToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

/** An {@link UnresolvedLuaPlatform} based on .buckconfig values. */
public class ConfigBasedUnresolvedLuaPlatform implements UnresolvedLuaPlatform {

  private final String section;
  private final BuckConfig delegate;
  private final ExecutableFinder finder;
  private final UnresolvedCxxPlatform unresolvedCxxPlatform;

  private final NativeLinkStrategy nativeLinkStrategy;
  private final LuaPlatform.PackageStyle packageStyle;
  private final ToolProvider packager;
  private final Optional<UnconfiguredBuildTarget> luaCxxLibraryTarget;
  private final Optional<UnconfiguredBuildTarget> nativeStarterLibrary;

  public ConfigBasedUnresolvedLuaPlatform(
      String section,
      BuckConfig delegate,
      ExecutableFinder finder,
      UnresolvedCxxPlatform unresolvedCxxPlatform) {
    this.section = section;
    this.delegate = delegate;
    this.finder = finder;
    this.unresolvedCxxPlatform = unresolvedCxxPlatform;

    this.nativeLinkStrategy =
        delegate
            .getEnum(section, "native_link_strategy", NativeLinkStrategy.class)
            .orElse(NativeLinkStrategy.SEPARATE);
    this.packageStyle =
        delegate
            .getEnum(section, "package_style", LuaPlatform.PackageStyle.class)
            .orElse(LuaPlatform.PackageStyle.INPLACE);
    this.packager =
        delegate
            .getView(ToolConfig.class)
            .getToolProvider(section, "packager")
            .orElseGet(() -> ErrorToolProvider.from("no packager set in '%s.packager'", section));
    this.luaCxxLibraryTarget = delegate.getUnconfiguredBuildTarget(section, "cxx_library");
    this.nativeStarterLibrary =
        delegate.getUnconfiguredBuildTarget(section, "native_starter_library");
  }

  @Override
  public LuaPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return ImmutableLuaPlatform.builder()
        .setLua(
            delegate
                .getView(ToolConfig.class)
                .getToolProvider(section, "lua")
                .orElseGet(
                    () ->
                        SystemToolProvider.of(
                            finder,
                            delegate::getPathSourcePath,
                            Paths.get("lua"),
                            delegate.getEnvironment())))
        .setLuaCxxLibraryTarget(luaCxxLibraryTarget.map(t -> t.configure(targetConfiguration)))
        .setStarterType(
            delegate.getEnum(section, "starter_type", LuaBinaryDescription.StarterType.class))
        .setExtension(delegate.getValue(section, "extension").orElse(".lex"))
        .setNativeStarterLibrary(nativeStarterLibrary.map(t -> t.configure(targetConfiguration)))
        .setPackageStyle(packageStyle)
        .setPackager(packager)
        .setShouldCacheBinaries(delegate.getBooleanValue(section, "cache_binaries", true))
        .setNativeLinkStrategy(
            delegate
                .getEnum(section, "native_link_strategy", NativeLinkStrategy.class)
                .orElse(NativeLinkStrategy.SEPARATE))
        .setCxxPlatform(unresolvedCxxPlatform.resolve(resolver, targetConfiguration))
        .build();
  }

  @Override
  public NativeLinkStrategy getNativeLinkStrategy() {
    return nativeLinkStrategy;
  }

  @Override
  public Flavor getFlavor() {
    return unresolvedCxxPlatform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    if (packageStyle == LuaPlatform.PackageStyle.STANDALONE) {
      deps.addAll(packager.getParseTimeDeps(targetConfiguration));
    }
    luaCxxLibraryTarget.ifPresent(t -> deps.add(t.configure(targetConfiguration)));
    nativeStarterLibrary.ifPresent(t -> deps.add(t.configure(targetConfiguration)));
    deps.addAll(unresolvedCxxPlatform.getParseTimeDeps(targetConfiguration));
    return deps.build();
  }
}
