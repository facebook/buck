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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class PrebuiltPythonLibrary extends NoopBuildRule implements PythonPackagable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final SourcePath binarySrc;

  public PrebuiltPythonLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath binarySrc) {
    super(params, resolver);
    this.binarySrc = binarySrc;
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.of(binarySrc),
        Optional.<Boolean>absent());
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

}
