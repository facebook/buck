/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil.endtoend;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.testutil.PlatformUtils;
import java.util.Optional;

/**
 * Util class that provides various useful functions needed by many EndToEndTests and their
 * components
 */
public class EndToEndHelper {
  private static PlatformUtils platformUtils = PlatformUtils.getForPlatform();

  // util class, no instances
  private EndToEndHelper() {}

  /**
   * Given a fully qualified build target string, this will get the name for that target with
   * appropriate flavours.
   */
  public static String getProperBuildTarget(String target) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(target);
    Optional<String> platformFlavorName = platformUtils.getFlavor();
    if (platformFlavorName.isPresent()) {
      Flavor platformFlavor = UserFlavor.of(platformFlavorName.get(), platformFlavorName.get());
      buildTarget = buildTarget.withFlavors(platformFlavor);
    }
    return buildTarget.getFullyQualifiedName();
  }
}
