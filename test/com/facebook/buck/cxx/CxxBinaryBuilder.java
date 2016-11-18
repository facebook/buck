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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;

public class CxxBinaryBuilder
    extends
    AbstractCxxSourceBuilder<CxxBinaryDescription.Arg, CxxBinaryDescription, CxxBinaryBuilder> {

  public CxxBinaryBuilder(
      BuildTarget target,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new CxxBinaryDescription(
            CxxPlatformUtils.DEFAULT_CONFIG,
            new InferBuckConfig(FakeBuckConfig.builder().build()),
            defaultCxxPlatform,
            cxxPlatforms),
        target);
  }

  public CxxBinaryBuilder(
      BuildTarget target,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxBuckConfig cxxBuckConfig) {
    super(
        new CxxBinaryDescription(
            cxxBuckConfig,
            new InferBuckConfig(FakeBuckConfig.builder().build()),
            defaultCxxPlatform,
            cxxPlatforms),
        target);
  }

  public CxxBinaryBuilder(BuildTarget target) {
    this(target, createDefaultPlatform(), createDefaultPlatforms());
  }

  public CxxBinaryBuilder(BuildTarget target, CxxBuckConfig cxxBuckConfig) {
    this(target, createDefaultPlatform(), createDefaultPlatforms(), cxxBuckConfig);
  }

  @Override
  protected CxxBinaryBuilder getThis() {
    return this;
  }

}
