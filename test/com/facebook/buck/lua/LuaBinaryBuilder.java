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
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.python.PythonTestUtils;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class LuaBinaryBuilder extends AbstractNodeBuilder<LuaBinaryDescription.Arg> {

  public LuaBinaryBuilder(
      Description<LuaBinaryDescription.Arg> description,
      BuildTarget target) {
    super(description, target);
  }

  public LuaBinaryBuilder(
      BuildTarget target,
      LuaConfig config,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    this(
        new LuaBinaryDescription(
            config,
            cxxBuckConfig,
            defaultCxxPlatform,
            cxxPlatforms,
            pythonPlatforms),
        target);
  }

  public LuaBinaryBuilder(BuildTarget target, LuaConfig config) {
    this(
        target,
        config,
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxPlatformUtils.DEFAULT_PLATFORMS,
        PythonTestUtils.PYTHON_PLATFORMS);
  }

  public LuaBinaryBuilder(BuildTarget target) {
    this(target, FakeLuaConfig.DEFAULT);
  }

  public LuaBinaryBuilder setMainModule(String mainModule) {
    arg.mainModule = mainModule;
    return this;
  }

  public LuaBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

  public LuaBinaryBuilder setPackageStyle(LuaConfig.PackageStyle packageStyle) {
    arg.packageStyle = Optional.of(packageStyle);
    return this;
  }

  public LuaBinaryBuilder setNativeStarterLibrary(BuildTarget target) {
    arg.nativeStarterLibrary = Optional.of(target);
    return this;
  }

}
