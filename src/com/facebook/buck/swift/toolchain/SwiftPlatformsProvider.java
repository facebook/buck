/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.swift.SwiftPlatform;
import com.google.common.collect.ImmutableMap;

public class SwiftPlatformsProvider {

  private final FlavorDomain<SwiftPlatform> swiftCxxPlatforms;

  SwiftPlatformsProvider(FlavorDomain<SwiftPlatform> swiftCxxPlatforms) {
    this.swiftCxxPlatforms = swiftCxxPlatforms;
  }

  public FlavorDomain<SwiftPlatform> getSwiftCxxPlatforms() {
    return swiftCxxPlatforms;
  }

  public static SwiftPlatformsProvider create(AppleCxxPlatformsProvider appleCxxPlatformsProvider) {

    FlavorDomain<AppleCxxPlatform> appleCxxPlatforms =
        appleCxxPlatformsProvider.getAppleCxxPlatforms();

    ImmutableMap.Builder<Flavor, SwiftPlatform> swiftPlatforms = ImmutableMap.builder();
    for (Flavor flavor : appleCxxPlatforms.getFlavors()) {
      appleCxxPlatforms
          .getValue(flavor)
          .getSwiftPlatform()
          .ifPresent(swiftPlatform -> swiftPlatforms.put(flavor, swiftPlatform));
    }
    FlavorDomain<SwiftPlatform> swiftPlatformDomain =
        new FlavorDomain<>("Swift Platform", swiftPlatforms.build());

    return new SwiftPlatformsProvider(swiftPlatformDomain);
  }
}
