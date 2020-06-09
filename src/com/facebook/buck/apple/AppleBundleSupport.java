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

import com.facebook.buck.core.rules.BuildRule;
import java.util.Optional;

/** Utility functions helpful when implementing AppleBundle build rule & friends */
public class AppleBundleSupport {
  private AppleBundleSupport() {}

  /** Returns whether binary is a legacy watchOS application */
  public static boolean isLegacyWatchApp(String extension, Optional<BuildRule> maybeBinary) {
    return extension.equals(AppleBundleExtension.APP.toFileExtension())
        && maybeBinary.isPresent()
        && maybeBinary
            .get()
            .getBuildTarget()
            .getFlavors()
            .contains(AppleBinaryDescription.LEGACY_WATCH_FLAVOR);
  }
}
