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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.UserFlavor;

public enum AppleDebugFormat implements FlavorConvertible {
  /** Produces a binary with the debug map stripped. */
  NONE(UserFlavor.of("no-debug", "Produces a binary with the debug map stripped")),

  /** Produces an unstripped binary. */
  DWARF(UserFlavor.of("dwarf", "Produces an unstripped binary")),

  /** Generate a .dSYM file from the binary and its constituent object files. */
  DWARF_AND_DSYM(
      UserFlavor.of(
          "dwarf-and-dsym",
          "Generate a .dSYM file from the binary and its constituent object files")),
  ;

  private final Flavor flavor;

  AppleDebugFormat(Flavor flavor) {
    this.flavor = flavor;
  }

  public static final FlavorDomain<AppleDebugFormat> FLAVOR_DOMAIN =
      FlavorDomain.from("Debug Info Format Type", AppleDebugFormat.class);

  @Override
  public Flavor getFlavor() {
    return flavor;
  }
}
