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

package com.facebook.buck.python;

import com.facebook.buck.cxx.AbstractCxxSourceBuilder;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class CxxPythonExtensionBuilder extends
    AbstractCxxSourceBuilder<CxxPythonExtensionDescription.Arg, CxxPythonExtensionBuilder> {

  public CxxPythonExtensionBuilder(
      BuildTarget target,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      PythonBuckConfig pythonBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(new CxxPythonExtensionDescription(pythonPlatforms, pythonBuckConfig, cxxBuckConfig, cxxPlatforms), target);
  }

  public CxxPythonExtensionBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public CxxPythonExtensionBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    arg.platformDeps = Optional.of(platformDeps);
    return this;
  }

  @Override
  protected CxxPythonExtensionBuilder getThis() {
    return this;
  }

}
