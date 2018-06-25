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


package com.facebook.buck.android;

import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.log.Logger;
import java.util.EnumSet;

public class ExopackageArgsHelper {

  private static final Logger LOG = Logger.get(ExopackageArgsHelper.class);

  static EnumSet<ExopackageMode> detectExopackageModes(
      BuildTarget buildTarget, HasExopackageArgs exopackageArgs) {
    EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
    if (!exopackageArgs.getExopackageModes().isEmpty()) {
      exopackageModes = EnumSet.copyOf(exopackageArgs.getExopackageModes());
    } else if (exopackageArgs.isExopackage().orElse(false)) {
      LOG.error(
          "Target %s specified exopackage=True, which is deprecated. Use exopackage_modes.",
          buildTarget);
      exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
    }
    return exopackageModes;
  }
}
