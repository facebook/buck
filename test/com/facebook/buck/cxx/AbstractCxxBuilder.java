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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.google.common.collect.ImmutableMap;

public class AbstractCxxBuilder<T> extends AbstractNodeBuilder<T> {

  public AbstractCxxBuilder(Description<T> description, BuildTarget target) {
    super(description, target);
  }

  public static CxxBuckConfig createDefaultConfig() {
    BuckConfig buckConfig = new FakeBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    return cxxBuckConfig;
  }

  public static CxxPlatform createDefaultPlatform() {
    BuckConfig buckConfig = new FakeBuckConfig();
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(buckConfig);
    return cxxPlatform;
  }

  public static FlavorDomain<CxxPlatform> createDefaultPlatforms() {
    BuckConfig buckConfig = new FakeBuckConfig();
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(buckConfig);
    FlavorDomain<CxxPlatform> cxxPlatforms = new FlavorDomain<>(
        "C/C++ Platform",
        ImmutableMap.of(cxxPlatform.getFlavor(), cxxPlatform));
    return cxxPlatforms;
  }

}
