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
import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.cxx.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class LuaBuckConfig implements LuaConfig {

  private static final String SECTION = "lua";
  private static final AbstractCxxLibrary SYSTEM_CXX_LIBRARY =
      new SystemLuaCxxLibrary(
          BuildTarget.of(
              UnflavoredBuildTarget.of(Paths.get(""), Optional.empty(), "//system", "lua")));

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public LuaBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  @VisibleForTesting
  protected Optional<Path> getSystemLua() {
    return finder.getOptionalExecutable(Paths.get("lua"), delegate.getEnvironment());
  }

  @Override
  public Tool getLua(BuildRuleResolver resolver) {
    Optional<Tool> configuredLua = delegate.getTool(SECTION, "lua", resolver);
    if (configuredLua.isPresent()) {
      return configuredLua.get();
    }

    Optional<Path> systemLua = getSystemLua();
    if (systemLua.isPresent()) {
      return new HashedFileTool(systemLua.get());
    }

    throw new HumanReadableException(
        "No lua interpreter found in .buckconfig (%s.lua) or on system", SECTION);
  }

  @Override
  public Optional<BuildTarget> getLuaCxxLibraryTarget() {
    return delegate.getBuildTarget(SECTION, "cxx_library");
  }

  @Override
  public AbstractCxxLibrary getLuaCxxLibrary(BuildRuleResolver resolver) {
    Optional<BuildTarget> luaCxxLibrary = getLuaCxxLibraryTarget();
    if (luaCxxLibrary.isPresent()) {
      Optional<AbstractCxxLibrary> rule =
          resolver.getRuleOptionalWithType(luaCxxLibrary.get(), AbstractCxxLibrary.class);
      if (!rule.isPresent()) {
        throw new HumanReadableException(
            ".buckconfig: cannot find C/C++ library rule %s", luaCxxLibrary.get());
      }
      return rule.get();
    }
    return SYSTEM_CXX_LIBRARY;
  }

  @Override
  public Optional<LuaBinaryDescription.StarterType> getStarterType() {
    return delegate.getEnum(SECTION, "starter_type", LuaBinaryDescription.StarterType.class);
  }

  @Override
  public String getExtension() {
    return delegate.getValue(SECTION, "extension").orElse(".lex");
  }

  @Override
  public Optional<BuildTarget> getNativeStarterLibrary() {
    return delegate.getBuildTarget(SECTION, "native_starter_library");
  }

  @Override
  public PackageStyle getPackageStyle() {
    return delegate
        .getEnum(SECTION, "package_style", PackageStyle.class)
        .orElse(PackageStyle.INPLACE);
  }

  @Override
  public ToolProvider getPackager() {
    Optional<ToolProvider> packager = delegate.getToolProvider(SECTION, "packager");
    if (!packager.isPresent()) {
      throw new HumanReadableException("no packager set in '%s.packager'", SECTION);
    }
    return packager.get();
  }

  @Override
  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(SECTION, "cache_binaries", true);
  }

  @Override
  public NativeLinkStrategy getNativeLinkStrategy() {
    return delegate
        .getEnum(SECTION, "native_link_strategy", NativeLinkStrategy.class)
        .orElse(NativeLinkStrategy.SEPARATE);
  }
}
