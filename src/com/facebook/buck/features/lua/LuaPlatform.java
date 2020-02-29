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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.AbstractCxxLibraryGroup;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import java.util.Optional;

@BuckStyleValueWithBuilder
public abstract class LuaPlatform implements FlavorConvertible {

  public static final String FLAVOR_DOMAIN_NAME = "Lua Platform";

  @Override
  public Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }

  public abstract ToolProvider getLua();

  public abstract Optional<BuildTarget> getNativeStarterLibrary();

  public abstract Optional<BuildTarget> getLuaCxxLibraryTarget();

  public abstract Optional<LuaBinaryDescription.StarterType> getStarterType();

  public abstract String getExtension();

  /** @return the {@link PackageStyle} to use for Lua executables. */
  public abstract PackageStyle getPackageStyle();

  /** @return the {@link ToolProvider} which packages standalone Lua executables. */
  public abstract ToolProvider getPackager();

  /** @return whether to cache Lua executable packages. */
  public abstract boolean shouldCacheBinaries();

  /** @return the native link strategy to use for binaries. */
  public abstract NativeLinkStrategy getNativeLinkStrategy();

  public abstract CxxPlatform getCxxPlatform();

  public AbstractCxxLibraryGroup getLuaCxxLibrary(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return getLuaCxxLibraryTarget()
        .map(
            target ->
                resolver
                    .getRuleOptionalWithType(target, AbstractCxxLibraryGroup.class)
                    .<RuntimeException>orElseThrow(
                        () ->
                            new HumanReadableException(
                                "Cannot find C/C++ library rule %s", target)))
        .orElse(
            new SystemLuaCxxLibrary(
                UnconfiguredBuildTarget.of(BaseName.of("//system"), "lua")
                    .configure(targetConfiguration)));
  }

  public LuaPlatform withLua(ToolProvider lua) {
    if (getLua().equals(lua)) {
      return this;
    }
    return ImmutableLuaPlatform.builder().from(this).setLua(lua).build();
  }

  public LuaPlatform withPackageStyle(PackageStyle packageStyle) {
    if (getPackageStyle().equals(packageStyle)) {
      return this;
    }
    return ImmutableLuaPlatform.builder().from(this).setPackageStyle(packageStyle).build();
  }

  public LuaPlatform withNativeLinkStrategy(NativeLinkStrategy nativeLinkStrategy) {
    if (getNativeLinkStrategy().equals(nativeLinkStrategy)) {
      return this;
    }
    return ImmutableLuaPlatform.builder()
        .from(this)
        .setNativeLinkStrategy(nativeLinkStrategy)
        .build();
  }

  public LuaPlatform withExtension(String extension) {
    if (getExtension().equals(extension)) {
      return this;
    }
    return ImmutableLuaPlatform.builder().from(this).setExtension(extension).build();
  }

  protected enum PackageStyle {

    /** Build Lua executables into standalone, relocatable packages. */
    STANDALONE,

    /** Build Lua executables that can only be run from their build location. */
    INPLACE,
  }
}
