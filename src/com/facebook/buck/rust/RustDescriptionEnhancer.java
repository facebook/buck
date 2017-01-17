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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.ImmutableFlavor;

/**
 * Rust-specific flavors.
 */
public class RustDescriptionEnhancer {
  private RustDescriptionEnhancer() {}

  public static final Flavor RFBIN = ImmutableFlavor.of("binary");
  public static final Flavor RFLIB = ImmutableFlavor.of("lib");
  public static final Flavor RFRLIB = ImmutableFlavor.of("rlib");
  public static final Flavor RFRLIB_PIC = ImmutableFlavor.of("rlib-pic");
  public static final Flavor RFDYLIB = ImmutableFlavor.of("dylib");

  /**
   * Flavor of Rust crate
   *
   * Corresponds to https://doc.rust-lang.org/reference.html#linkage
   */
  enum Type implements FlavorConvertible {
    BIN(RustDescriptionEnhancer.RFBIN, CrateType.BIN),
    LIB(RustDescriptionEnhancer.RFLIB, CrateType.LIB),
    RLIB(RustDescriptionEnhancer.RFRLIB, CrateType.RLIB),
    RLIB_PIC(RustDescriptionEnhancer.RFRLIB_PIC, CrateType.RLIB_PIC),
    DYLIB(RustDescriptionEnhancer.RFDYLIB, CrateType.DYLIB),
    STATICLIB(CxxDescriptionEnhancer.STATIC_FLAVOR, CrateType.STATIC),
    CDYLIB(CxxDescriptionEnhancer.SHARED_FLAVOR, CrateType.CDYLIB),
    ;

    private final Flavor flavor;
    private final CrateType crateType;

    Type(Flavor flavor, CrateType crateType) {
      this.flavor = flavor;
      this.crateType = crateType;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    public CrateType getCrateType() {
      return crateType;
    }
  }

  @FunctionalInterface
  interface FilenameMap {
    String apply(String name, CxxPlatform cxxPlatform);
  }
}
