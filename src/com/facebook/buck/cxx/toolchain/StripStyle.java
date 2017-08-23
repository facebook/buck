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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.google.common.collect.ImmutableList;

/** Defines the level of symbol stripping to be performed on the linked product of the build. */
public enum StripStyle implements FlavorConvertible {
  /** Strips debugging symbols, but saves local and global symbols. */
  DEBUGGING_SYMBOLS(InternalFlavor.of("strip-debug"), ImmutableList.of("-S")),

  /**
   * Strips non-global symbols, but saves external symbols. This is preferred for dynamic shared
   * libraries.
   */
  NON_GLOBAL_SYMBOLS(InternalFlavor.of("strip-non-global"), ImmutableList.of("-x")),

  /**
   * Completely strips the binary, removing the symbol table and relocation information. This is
   * preferred for executable files.
   */
  ALL_SYMBOLS(InternalFlavor.of("strip-all"), ImmutableList.of()),
  ;

  private final Flavor flavor;
  private final ImmutableList<String> stripToolArgs;

  StripStyle(Flavor flavor, ImmutableList<String> stripToolArgs) {
    this.flavor = flavor;
    this.stripToolArgs = stripToolArgs;
  }

  public ImmutableList<String> getStripToolArgs() {
    return stripToolArgs;
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  public static final FlavorDomain<StripStyle> FLAVOR_DOMAIN =
      FlavorDomain.from("Strip Style", StripStyle.class);
}
