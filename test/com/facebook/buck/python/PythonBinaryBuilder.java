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

package com.facebook.buck.python;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class PythonBinaryBuilder extends AbstractNodeBuilder<PythonBinaryDescription.Arg> {

  public PythonBinaryBuilder(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new PythonBinaryDescription(
            pythonBuckConfig,
            pythonPlatforms,
            defaultCxxPlatform,
            cxxPlatforms),
        target);
  }

  public static PythonBinaryBuilder create(
      BuildTarget target,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder());
    return new PythonBinaryBuilder(
        target,
        pythonBuckConfig,
        pythonPlatforms,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public static PythonBinaryBuilder create(BuildTarget target) {
    return create(target, PythonTestUtils.PYTHON_PLATFORMS);
  }

  public PythonBinaryBuilder setMainModule(String mainModule) {
    arg.mainModule = Optional.of(mainModule);
    return this;
  }

  public PythonBinaryBuilder setMain(SourcePath main) {
    arg.main = Optional.of(main);
    return this;
  }

  public PythonBinaryBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public PythonBinaryBuilder setZipSafe(boolean zipSafe) {
    arg.zipSafe = Optional.fromNullable(zipSafe);
    return this;
  }

  public PythonBinaryBuilder setBuildArgs(ImmutableList<String> buildArgs) {
    arg.buildArgs = Optional.of(buildArgs);
    return this;
  }

  public PythonBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.fromNullable(deps);
    return this;
  }

  public PythonBinaryBuilder setPreloadDeps(ImmutableSet<BuildTarget> deps) {
    arg.preloadDeps = Optional.fromNullable(deps);
    return this;
  }

  public PythonBinaryBuilder setPlatform(String platform) {
    arg.platform = Optional.of(platform);
    return this;
  }

  public PythonBinaryBuilder setPackageStyle(PythonBuckConfig.PackageStyle packageStyle) {
    arg.packageStyle = Optional.of(packageStyle);
    return this;
  }

}
