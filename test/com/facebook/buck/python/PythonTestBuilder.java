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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class PythonTestBuilder
    extends AbstractNodeBuilder<
        PythonTestDescriptionArg.Builder, PythonTestDescriptionArg, PythonTestDescription,
        PythonTest> {

  protected PythonTestBuilder(
      BuildTarget target,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new PythonTestDescription(
            new PythonBinaryDescription(
                pythonBuckConfig,
                pythonPlatforms,
                CxxPlatformUtils.DEFAULT_CONFIG,
                defaultCxxPlatform,
                cxxPlatforms),
            pythonBuckConfig,
            pythonPlatforms,
            CxxPlatformUtils.DEFAULT_CONFIG,
            defaultCxxPlatform,
            Optional.empty(),
            cxxPlatforms),
        target);
  }

  public static PythonTestBuilder create(
      BuildTarget target, FlavorDomain<PythonPlatform> pythonPlatforms) {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder());
    return new PythonTestBuilder(
        target,
        pythonBuckConfig,
        pythonPlatforms,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public static PythonTestBuilder create(BuildTarget target) {
    return create(target, PythonTestUtils.PYTHON_PLATFORMS);
  }

  public PythonTestBuilder setSrcs(SourceList srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public PythonTestBuilder setPlatformSrcs(PatternMatchedCollection<SourceList> platformSrcs) {
    getArgForPopulating().setPlatformSrcs(platformSrcs);
    return this;
  }

  public PythonTestBuilder setPlatformResources(
      PatternMatchedCollection<SourceList> platformResources) {
    getArgForPopulating().setPlatformResources(platformResources);
    return this;
  }

  public PythonTestBuilder setBaseModule(String baseModule) {
    getArgForPopulating().setBaseModule(Optional.of(baseModule));
    return this;
  }

  public PythonTestBuilder setBuildArgs(ImmutableList<String> buildArgs) {
    getArgForPopulating().setBuildArgs(buildArgs);
    return this;
  }

  public PythonTestBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PythonTestBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> deps) {
    getArgForPopulating().setPlatformDeps(deps);
    return this;
  }

  public PythonTestBuilder setPlatform(String platform) {
    getArgForPopulating().setPlatform(Optional.of(platform));
    return this;
  }

  public PythonTestBuilder setCxxPlatform(Flavor platform) {
    getArgForPopulating().setCxxPlatform(Optional.of(platform));
    return this;
  }

  public PythonTestBuilder setPackageStyle(PythonBuckConfig.PackageStyle packageStyle) {
    getArgForPopulating().setPackageStyle(Optional.of(packageStyle));
    return this;
  }

  public PythonTestBuilder setVersionedSrcs(VersionMatchedCollection<SourceList> versionedSrcs) {
    getArgForPopulating().setVersionedSrcs(Optional.of(versionedSrcs));
    return this;
  }

  public PythonTestBuilder setVersionedResources(
      VersionMatchedCollection<SourceList> versionedResources) {
    getArgForPopulating().setVersionedResources(Optional.of(versionedResources));
    return this;
  }

  @Override
  public PythonTestBuilder setSelectedVersions(
      ImmutableMap<BuildTarget, Version> selectedVersions) {
    super.setSelectedVersions(selectedVersions);
    return this;
  }
}
