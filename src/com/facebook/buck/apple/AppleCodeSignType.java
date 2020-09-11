/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import java.util.Optional;

/** Type describing if and how codesign tool should be run when assembling the bundle */
public enum AppleCodeSignType {
  SKIP,
  ADHOC,
  DISTRIBUTION,
  ;

  /** Detect needed code sign type */
  public static AppleCodeSignType signTypeForBundle(
      ApplePlatform platform, String extension, Optional<AppleCodeSignType> override) {
    if (override.isPresent()) {
      return override.get();
    }

    boolean isCodeSignNeeded =
        ApplePlatform.needsCodeSign(platform.getName())
            // .framework bundles will be code-signed when they're copied into the containing
            // bundle.
            && !extension.equals(AppleBundleExtension.FRAMEWORK.fileExtension);
    if (!isCodeSignNeeded) {
      return SKIP;
    }
    boolean isAdhocSufficient = ApplePlatform.adHocCodeSignIsSufficient(platform.getName());
    return isAdhocSufficient ? ADHOC : DISTRIBUTION;
  }
}
