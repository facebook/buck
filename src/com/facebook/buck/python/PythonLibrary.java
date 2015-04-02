/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class PythonLibrary extends NoopBuildRule implements PythonPackagable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final ImmutableMap<Path, SourcePath> srcs;
  private final ImmutableMap<Path, SourcePath> resources;

  public PythonLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableMap<Path, SourcePath> srcs,
      ImmutableMap<Path, SourcePath> resources) {
    super(params, resolver);
    this.srcs = srcs;
    this.resources = resources;
  }

  /**
   * Return the components to contribute to the top-level python package.
   */
  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    return ImmutablePythonPackageComponents.of(
        srcs,
        resources,
        ImmutableMap.<Path, SourcePath>of());
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public ImmutableMap<Path, SourcePath> getSrcs() {
    return srcs;
  }

}
