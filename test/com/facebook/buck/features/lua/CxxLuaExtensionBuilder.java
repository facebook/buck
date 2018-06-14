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

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class CxxLuaExtensionBuilder
    extends AbstractNodeBuilder<
        CxxLuaExtensionDescriptionArg.Builder,
        CxxLuaExtensionDescriptionArg,
        CxxLuaExtensionDescription,
        CxxLuaExtension> {

  public CxxLuaExtensionBuilder(CxxLuaExtensionDescription description, BuildTarget target) {
    super(description, target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target, LuaPlatform luaPlatform) {
    this(
        new CxxLuaExtensionDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    LuaPlatformsProvider.DEFAULT_NAME,
                    LuaPlatformsProvider.of(
                        luaPlatform, FlavorDomain.of(LuaPlatform.FLAVOR_DOMAIN_NAME, luaPlatform)))
                .build(),
            new CxxBuckConfig(FakeBuckConfig.builder().build())),
        target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target) {
    this(target, LuaTestUtils.DEFAULT_PLATFORM);
  }

  public CxxLuaExtensionBuilder setBaseModule(String baseModule) {
    getArgForPopulating().setBaseModule(Optional.of(baseModule));
    return this;
  }

  public CxxLuaExtensionBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public CxxLuaExtensionBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public CxxLuaExtensionBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofNamedSources(headers));
    return this;
  }

  public CxxLuaExtensionBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(StringWithMacrosUtils.fromStrings(compilerFlags));
    return this;
  }

  public CxxLuaExtensionBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public CxxLuaExtensionBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public CxxLuaExtensionBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public CxxLuaExtensionBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }
}
