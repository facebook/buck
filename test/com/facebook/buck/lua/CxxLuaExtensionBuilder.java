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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilderWithMutableArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class CxxLuaExtensionBuilder
    extends AbstractNodeBuilderWithMutableArg<
        CxxLuaExtensionDescription.Arg, CxxLuaExtensionDescription, CxxLuaExtension> {

  public CxxLuaExtensionBuilder(CxxLuaExtensionDescription description, BuildTarget target) {
    super(description, target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target, LuaConfig config) {
    this(
        new CxxLuaExtensionDescription(
            config,
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS),
        target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target) {
    this(target, FakeLuaConfig.DEFAULT);
  }

  public CxxLuaExtensionBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public CxxLuaExtensionBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    arg.srcs = srcs;
    return this;
  }

  public CxxLuaExtensionBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    arg.headers = SourceList.ofUnnamedSources(headers);
    return this;
  }

  public CxxLuaExtensionBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    arg.headers = SourceList.ofNamedSources(headers);
    return this;
  }

  public CxxLuaExtensionBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    arg.compilerFlags = compilerFlags;
    return this;
  }

  public CxxLuaExtensionBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    arg.linkerFlags = linkerFlags;
    return this;
  }

  public CxxLuaExtensionBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    arg.frameworks = frameworks;
    return this;
  }

  public CxxLuaExtensionBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    arg.libraries = libraries;
    return this;
  }

  public CxxLuaExtensionBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }
}
