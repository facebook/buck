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

package com.facebook.buck.android.exopackage;

import java.util.EnumSet;

public enum ExopackageMode {
  SECONDARY_DEX(1),
  NATIVE_LIBRARY(2),
  RESOURCES(4),
  MODULES(8),
  ARCH64(16),
  ;

  private final int code;

  ExopackageMode(int code) {
    this.code = code;
  }

  public static boolean enabledForSecondaryDexes(EnumSet<ExopackageMode> modes) {
    return modes.contains(SECONDARY_DEX);
  }

  public static boolean enabledForNativeLibraries(EnumSet<ExopackageMode> modes) {
    return modes.contains(NATIVE_LIBRARY);
  }

  public static boolean enabledForResources(EnumSet<ExopackageMode> modes) {
    return modes.contains(RESOURCES);
  }

  public static boolean enabledForModules(EnumSet<ExopackageMode> modes) {
    return modes.contains(MODULES);
  }

  public static boolean enabledForArch64(EnumSet<ExopackageMode> modes) {
    return modes.contains(ARCH64);
  }

  public static int toBitmask(EnumSet<ExopackageMode> modes) {
    int bitmask = 0;
    for (ExopackageMode mode : modes) {
      bitmask |= mode.code;
    }
    return bitmask;
  }
}
