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
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.features.python.PythonTestUtils;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
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
      LuaPlatform defaultPlatform,
      FlavorDomain<LuaPlatform> luaPlatforms,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    this(
        new LuaBinaryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    LuaPlatformsProvider.DEFAULT_NAME,
                    LuaPlatformsProvider.of(defaultPlatform, luaPlatforms))
                .withToolchain(
                    PythonPlatformsProvider.DEFAULT_NAME,
                    PythonPlatformsProvider.of(pythonPlatforms))
                .build(),
            cxxBuckConfig),
        target);
  }

  public LuaBinaryBuilder(BuildTarget target, LuaPlatform luaPlatform) {
    this(
        target,
        luaPlatform,
        FlavorDomain.of(LuaPlatform.FLAVOR_DOMAIN_NAME, luaPlatform),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        PythonTestUtils.PYTHON_PLATFORMS);
  }

  public LuaBinaryBuilder(BuildTarget target) {
    this(target, LuaTestUtils.DEFAULT_PLATFORM);
  }

  public LuaBinaryBuilder setMainModule(String mainModule) {
    getArgForPopulating().setMainModule(mainModule);
    return this;
  }

  public LuaBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public LuaBinaryBuilder setPackageStyle(LuaPlatform.PackageStyle packageStyle) {
    getArgForPopulating().setPackageStyle(Optional.of(packageStyle));
    return this;
  }

  public LuaBinaryBuilder setNativeStarterLibrary(BuildTarget target) {
    getArgForPopulating().setNativeStarterLibrary(Optional.of(target));
    return this;
  }

  public LuaBinaryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> deps) {
    getArgForPopulating().setPlatformDeps(deps);
    return this;
  }
}
