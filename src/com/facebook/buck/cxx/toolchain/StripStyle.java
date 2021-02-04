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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;

/** Defines the level of symbol stripping to be performed on the linked product of the build. */
public enum StripStyle implements FlavorConvertible {
  /** Strips debugging symbols, but saves local and global symbols. */
  DEBUGGING_SYMBOLS(InternalFlavor.of("strip-debug")),

  /**
   * Strips non-global symbols, but saves external symbols. This is preferred for dynamic shared
   * libraries.
   */
  NON_GLOBAL_SYMBOLS(InternalFlavor.of("strip-non-global")),

  /**
   * Completely strips the binary, removing the symbol table and relocation information. This is
   * preferred for executable files.
   */
  ALL_SYMBOLS(InternalFlavor.of("strip-all")),
  ;

  private final Flavor flavor;

  StripStyle(Flavor flavor) {
    this.flavor = flavor;
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  public static final FlavorDomain<StripStyle> FLAVOR_DOMAIN =
      FlavorDomain.from("Strip Style", StripStyle.class);
}
