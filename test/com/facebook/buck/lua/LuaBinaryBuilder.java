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
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class LuaBinaryBuilder
    extends AbstractNodeBuilder<
        LuaBinaryDescriptionArg.Builder, LuaBinaryDescriptionArg, LuaBinaryDescription, LuaBinary> {

  public LuaBinaryBuilder(LuaBinaryDescription description, BuildTarget target) {
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
            config, cxxBuckConfig, defaultCxxPlatform, cxxPlatforms, pythonPlatforms),
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
    getArgForPopulating().setMainModule(mainModule);
    return this;
  }

  public LuaBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public LuaBinaryBuilder setPackageStyle(LuaConfig.PackageStyle packageStyle) {
    getArgForPopulating().setPackageStyle(Optional.of(packageStyle));
    return this;
  }

  public LuaBinaryBuilder setNativeStarterLibrary(BuildTarget target) {
    getArgForPopulating().setNativeStarterLibrary(Optional.of(target));
    return this;
  }
}
