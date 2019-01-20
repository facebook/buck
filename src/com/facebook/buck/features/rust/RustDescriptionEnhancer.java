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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import java.util.Optional;

/** Rust-specific flavors. */
public class RustDescriptionEnhancer {
  private RustDescriptionEnhancer() {}

  public static final Flavor RFBIN = InternalFlavor.of("binary");
  public static final Flavor RFLIB = InternalFlavor.of("lib");
  public static final Flavor RFRLIB = InternalFlavor.of("rlib");
  public static final Flavor RFRLIB_PIC = InternalFlavor.of("rlib-pic");
  public static final Flavor RFDYLIB = InternalFlavor.of("dylib");
  public static final Flavor RFPROC_MACRO = InternalFlavor.of("proc-macro");
  public static final Flavor RFCHECK =
      UserFlavor.of(
          "check", "Quickly check code and generate metadata about crate, without generating code");
  public static final Flavor RFSAVEANALYSIS =
      UserFlavor.of("save-analysis", "Generate type analysis data");

  /**
   * Flavor of Rust crate
   *
   * <p>Corresponds to https://doc.rust-lang.org/reference.html#linkage
   */
  enum Type implements FlavorConvertible {
    BIN(RustDescriptionEnhancer.RFBIN, CrateType.BIN),
    LIB(RustDescriptionEnhancer.RFLIB, CrateType.LIB),
    RLIB(RustDescriptionEnhancer.RFRLIB, CrateType.RLIB),
    RLIB_PIC(RustDescriptionEnhancer.RFRLIB_PIC, CrateType.RLIB_PIC),
    DYLIB(RustDescriptionEnhancer.RFDYLIB, CrateType.DYLIB),
    STATICLIB(CxxDescriptionEnhancer.STATIC_FLAVOR, CrateType.STATIC),
    CDYLIB(CxxDescriptionEnhancer.SHARED_FLAVOR, CrateType.CDYLIB),
    CHECK(RustDescriptionEnhancer.RFCHECK, CrateType.CHECK),
    SAVEANALYSIS(RustDescriptionEnhancer.RFSAVEANALYSIS, CrateType.SAVEANALYSIS),
    PROC_MACRO(RustDescriptionEnhancer.RFPROC_MACRO, CrateType.PROC_MACRO),
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
    Optional<String> apply(BuildTarget target, String name, CxxPlatform cxxPlatform);
  }
}
