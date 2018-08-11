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

package com.facebook.buck.features.python;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.features.python.toolchain.impl.DefaultPexToolProvider;
import com.facebook.buck.features.python.toolchain.impl.PythonInterpreterFromConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class PythonBinaryBuilder
    extends AbstractNodeBuilder<
        PythonBinaryDescriptionArg.Builder,
        PythonBinaryDescriptionArg,
        PythonBinaryDescription,
        PythonBinary> {

  private PythonBinaryBuilder(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      ToolchainProviderBuilder toolchainProviderBuilder) {
    super(
        new PythonBinaryDescription(
            toolchainProviderBuilder.build(), pythonBuckConfig, CxxPlatformUtils.DEFAULT_CONFIG),
        target);
  }

  private PythonBinaryBuilder(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      ExecutableFinder executableFinder,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this(
        target,
        pythonBuckConfig,
        new ToolchainProviderBuilder()
            .withToolchain(
                PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.of(pythonPlatforms))
            .withToolchain(
                PexToolProvider.DEFAULT_NAME,
                new DefaultPexToolProvider(
                    new ToolchainProviderBuilder()
                        .withToolchain(
                            PythonInterpreter.DEFAULT_NAME,
                            new PythonInterpreterFromConfig(pythonBuckConfig, executableFinder))
                        .build(),
                    pythonBuckConfig,
                    TestRuleKeyConfigurationFactory.create()))
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(defaultCxxPlatform, cxxPlatforms)));
  }

  public static PythonBinaryBuilder create(
      BuildTarget target, FlavorDomain<PythonPlatform> pythonPlatforms) {
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(FakeBuckConfig.builder().build());
    return new PythonBinaryBuilder(
        target,
        pythonBuckConfig,
        new ExecutableFinder(),
        pythonPlatforms,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public static PythonBinaryBuilder create(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    return new PythonBinaryBuilder(
        target,
        pythonBuckConfig,
        new ExecutableFinder(),
        pythonPlatforms,
        defaultCxxPlatform,
        cxxPlatforms);
  }

  public static PythonBinaryBuilder create(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    return new PythonBinaryBuilder(
        target,
        pythonBuckConfig,
        new ExecutableFinder(),
        pythonPlatforms,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public static PythonBinaryBuilder create(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      ToolchainProviderBuilder toolchainProviderBuilder,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    return new PythonBinaryBuilder(
        target,
        pythonBuckConfig,
        toolchainProviderBuilder
            .withToolchain(
                PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.of(pythonPlatforms))
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    CxxPlatformUtils.DEFAULT_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORMS)));
  }

  public static PythonBinaryBuilder create(BuildTarget target) {
    return create(target, PythonTestUtils.PYTHON_PLATFORMS);
  }

  public PythonBinaryBuilder setMainModule(String mainModule) {
    getArgForPopulating().setMainModule(Optional.of(mainModule));
    return this;
  }

  public PythonBinaryBuilder setMain(SourcePath main) {
    getArgForPopulating().setMain(Optional.of(main));
    return this;
  }

  public PythonBinaryBuilder setBaseModule(String baseModule) {
    getArgForPopulating().setBaseModule(Optional.of(baseModule));
    return this;
  }

  public PythonBinaryBuilder setExtension(String extension) {
    getArgForPopulating().setExtension(Optional.of(extension));
    return this;
  }

  public PythonBinaryBuilder setBuildArgs(ImmutableList<String> buildArgs) {
    getArgForPopulating().setBuildArgs(buildArgs);
    return this;
  }

  public PythonBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PythonBinaryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> deps) {
    getArgForPopulating().setPlatformDeps(deps);
    return this;
  }

  public PythonBinaryBuilder setPreloadDeps(ImmutableSet<BuildTarget> deps) {
    getArgForPopulating().setPreloadDeps(deps);
    return this;
  }

  public PythonBinaryBuilder setPlatform(String platform) {
    getArgForPopulating().setPlatform(Optional.of(platform));
    return this;
  }

  public PythonBinaryBuilder setCxxPlatform(Flavor platform) {
    getArgForPopulating().setCxxPlatform(Optional.of(platform));
    return this;
  }

  public PythonBinaryBuilder setPackageStyle(PythonBuckConfig.PackageStyle packageStyle) {
    getArgForPopulating().setPackageStyle(Optional.of(packageStyle));
    return this;
  }

  public PythonBinaryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }
}
