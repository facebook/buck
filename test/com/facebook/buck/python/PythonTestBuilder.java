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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonTestBuilder extends AbstractNodeBuilder<PythonTestDescription.Arg> {

  private PythonTestBuilder(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path pathToPex,
      Path pathToPexExecutor,
      String pexExtension,
      Path pathToPythonTestMain,
      PythonEnvironment pythonEnvironment,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new PythonTestDescription(
            filesystem,
            pathToPex,
            pathToPexExecutor,
            pexExtension,
            pathToPythonTestMain,
            pythonEnvironment,
            defaultCxxPlatform,
            cxxPlatforms),
        target);
  }

  public static PythonTestBuilder create(BuildTarget target) {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(new FakeBuckConfig(), new ExecutableFinder());
    return new PythonTestBuilder(
        target,
        new FakeProjectFilesystem(),
        pythonBuckConfig.getPathToPex(),
        pythonBuckConfig.getPathToPexExecuter(),
        pythonBuckConfig.getPexExtension(),
        pythonBuckConfig.getPathToTestMain(),
        new PythonEnvironment(Paths.get("python"), PythonVersion.of("2.7")),
        CxxPlatformUtils.DEFAULT_PLATFORM,
        new FlavorDomain<>(
            "C/C++ Platform",
            ImmutableMap.of(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxPlatformUtils.DEFAULT_PLATFORM)));
  }

  public PythonTestBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public PythonTestBuilder setZipSafe(boolean zipSafe) {
    arg.zipSafe = Optional.fromNullable(zipSafe);
    return this;
  }

  public PythonTestBuilder setBuildArgs(ImmutableList<String> buildArgs) {
    arg.buildArgs = Optional.of(buildArgs);
    return this;
  }

  public PythonTestBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.fromNullable(deps);
    return this;
  }

}
