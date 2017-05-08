/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilderWithMutableArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class CxxTestBuilder
    extends AbstractNodeBuilderWithMutableArg<CxxTestDescription.Arg, CxxTestDescription, CxxTest> {

  public CxxTestBuilder(
      BuildTarget target,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new CxxTestDescription(
            cxxBuckConfig,
            defaultCxxPlatform,
            cxxPlatforms,
            /* testRuleTimeoutMs */ Optional.empty()),
        target);
  }

  public CxxTestBuilder(BuildTarget target, CxxBuckConfig config) {
    this(target, config, CxxPlatformUtils.DEFAULT_PLATFORM, CxxTestUtils.createDefaultPlatforms());
  }

  public CxxTestBuilder setEnv(ImmutableMap<String, String> env) {
    arg.env = env;
    return this;
  }

  public CxxTestBuilder setArgs(ImmutableList<String> args) {
    arg.args = args;
    return this;
  }

  public CxxTestBuilder setRunTestSeparately(boolean runTestSeparately) {
    arg.runTestSeparately = Optional.of(runTestSeparately);
    return this;
  }

  public CxxTestBuilder setUseDefaultTestMain(boolean useDefaultTestMain) {
    arg.useDefaultTestMain = Optional.of(useDefaultTestMain);
    return this;
  }

  public CxxTestBuilder setFramework(CxxTestType framework) {
    arg.framework = Optional.of(framework);
    return this;
  }

  public CxxTestBuilder setResources(ImmutableSortedSet<Path> resources) {
    arg.resources = resources;
    return this;
  }

  public CxxTestBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    arg.srcs = srcs;
    return this;
  }

  public CxxTestBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    arg.headers = SourceList.ofUnnamedSources(headers);
    return this;
  }

  public CxxTestBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    arg.headers = SourceList.ofNamedSources(headers);
    return this;
  }

  public CxxTestBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    arg.compilerFlags = compilerFlags;
    return this;
  }

  public CxxTestBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    arg.linkerFlags = linkerFlags;
    return this;
  }

  public CxxTestBuilder setPlatformLinkerFlags(
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags) {
    arg.platformLinkerFlags = platformLinkerFlags;
    return this;
  }

  public CxxTestBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    arg.frameworks = frameworks;
    return this;
  }

  public CxxTestBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    arg.libraries = libraries;
    return this;
  }

  public CxxTestBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }
}
