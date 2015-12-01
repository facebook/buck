/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.collect.ImmutableMap;

public enum AppleDebugFormat {
  /**
   * Produces a binary with the debug map stripped.
   */
  NONE,

  /**
   * Generate a .dSYM file from the binary and its constituent object files.
   */
  DWARF_AND_DSYM;

  public static final Flavor DWARF_AND_DSYM_FLAVOR = ImmutableFlavor.of("dwarf-and-dsym");
  public static final Flavor NO_DEBUG_FLAVOR = ImmutableFlavor.of("no-debug");

  public static final FlavorDomain<AppleDebugFormat> FLAVOR_DOMAIN = new FlavorDomain<>(
      "Debug Info Format Type",
      ImmutableMap.<Flavor, AppleDebugFormat>builder()
          .put(DWARF_AND_DSYM_FLAVOR, AppleDebugFormat.DWARF_AND_DSYM)
          .put(NO_DEBUG_FLAVOR, AppleDebugFormat.NONE)
          .build());
}

