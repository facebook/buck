/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.util.HumanReadableException;
import java.util.Optional;

public class ApplePlatforms {
  // Utility class, do not instantiate.
  private ApplePlatforms() {}

  /** Only works with thin binaries. */
  static CxxPlatform getCxxPlatformForBuildTarget(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Flavor defaultCxxFlavor,
      BuildTarget target) {
    return cxxPlatformFlavorDomain
        .getValue(target)
        .orElse(cxxPlatformFlavorDomain.getValue(defaultCxxFlavor));
  }

  public static AppleCxxPlatform getAppleCxxPlatformForBuildTarget(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Flavor defaultCxxFlavor,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      BuildTarget target,
      Optional<MultiarchFileInfo> fatBinaryInfo) {
    AppleCxxPlatform appleCxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      appleCxxPlatform = fatBinaryInfo.get().getRepresentativePlatform();
    } else {
      CxxPlatform cxxPlatform =
          getCxxPlatformForBuildTarget(cxxPlatformFlavorDomain, defaultCxxFlavor, target);
      try {
        appleCxxPlatform = appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());
      } catch (FlavorDomainException e) {
        throw new HumanReadableException(
            e,
            "%s: Apple bundle requires an Apple platform, found '%s'\n\n"
                + "A common cause of this error is that the required SDK is missing.\n"
                + "Please check whether it's installed and retry.",
            target,
            cxxPlatform.getFlavor().getName());
      }
    }

    return appleCxxPlatform;
  }
}
