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

package com.facebook.buck.apple;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.google.common.collect.ImmutableMap;

public class AppleBinaryBuilder
    extends AbstractAppleNativeTargetBuilder<AppleNativeTargetDescriptionArg, AppleBinaryBuilder> {

  @Override
  protected AppleBinaryBuilder getThis() {
    return this;
  }

  protected AppleBinaryBuilder(BuildTarget target) {
    super(createDescription(), target);
  }

  private static AppleBinaryDescription createDescription() {
    BuckConfig buckConfig = new FakeBuckConfig();
    CxxPlatform cxxPlatform = new DefaultCxxPlatform(buckConfig);
    FlavorDomain<CxxPlatform> cxxPlatforms = new FlavorDomain<>(
        "C/C++ Platform",
        ImmutableMap.of(cxxPlatform.asFlavor(), cxxPlatform));
    return new AppleBinaryDescription(
        new AppleConfig(buckConfig),
        new CxxBinaryDescription(new CxxBuckConfig(buckConfig), cxxPlatform, cxxPlatforms));
  }


  public static AppleBinaryBuilder createBuilder(BuildTarget target) {
    return new AppleBinaryBuilder(target);
  }
}
