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
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;

/** Describe the kinds of crates rustc can generate. */
public enum CrateType {
  BIN(
      "bin",
      RustDescriptionEnhancer.RFBIN,
      (target, n, p) ->
          String.format("%s%s", n, p.getBinaryExtension().map(e -> "." + e).orElse(""))),
  CHECK(
      "lib",
      RustDescriptionEnhancer.RFCHECK,
      (target, crate, plat) -> hashed_filename(target, crate, "rmeta")),
  CHECKBIN(
      "bin",
      RustDescriptionEnhancer.RFCHECK,
      (target, n, p) ->
          String.format("%s%s", n, p.getBinaryExtension().map(e -> "." + e).orElse(""))),
  LIB(
      "lib",
      RustDescriptionEnhancer.RFLIB,
      (target, crate, plat) -> hashed_filename(target, crate, "rlib")), // XXX how to tell?
  RLIB(
      "rlib",
      RustDescriptionEnhancer.RFRLIB,
      (target, crate, plat) -> hashed_filename(target, crate, "rlib")),
  RLIB_PIC(
      "rlib",
      RustDescriptionEnhancer.RFRLIB_PIC,
      (target, crate, plat) -> hashed_filename(target, crate, "rlib")),
  DYLIB("dylib", RustDescriptionEnhancer.RFDYLIB, CrateType::dylib_filename),
  CDYLIB("cdylib", CxxDescriptionEnhancer.SHARED_FLAVOR, CrateType::dylib_filename),
  STATIC(
      "staticlib",
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      (target, n, p) -> hashed_filename(target, n, p.getStaticLibraryExtension())),
  STATIC_PIC(
      "staticlib",
      CxxDescriptionEnhancer.STATIC_PIC_FLAVOR,
      (target, n, p) -> hashed_filename(target, n, p.getStaticLibraryExtension())),
  PROC_MACRO("proc-macro", RustDescriptionEnhancer.RFPROC_MACRO, CrateType::dylib_filename),
  ;

  private static String hashed_filename(BuildTarget target, String crate, String ext) {
    String hash = RustCompileUtils.hashForTarget(target);
    return String.format("lib%s-%s.%s", crate, hash, ext);
  }

  private static String dylib_filename(BuildTarget target, String crate, CxxPlatform plat) {
    return hashed_filename(target, crate, plat.getSharedLibraryExtension());
  }

  // Crate type as passed to `rustc --crate-type=`
  private final String crateType;

  // Flavor corresponding to this crate type
  private final Flavor crateFlavor;

  // Function to consruct a suitable filename, using platform naming conventions
  private final RustDescriptionEnhancer.FilenameMap filenameMap;

  CrateType(String crateType, Flavor flavor, RustDescriptionEnhancer.FilenameMap filename) {
    this.crateType = crateType;
    this.crateFlavor = flavor;
    this.filenameMap = filename;
  }

  @Override
  public String toString() {
    return this.crateType;
  }

  public Flavor getFlavor() {
    return crateFlavor;
  }

  /**
   * Return true if this crate type is intended to be a native output (ie, not intended for further
   * processing by the Rust toolchain). In other words, a binary, or a native-linkable shared or
   * static object.
   *
   * @return Is natively usable.
   */
  public boolean isNative() {
    return this == BIN
        || this == CDYLIB
        || this == STATIC
        || this == STATIC_PIC
        || this == PROC_MACRO;
  }

  /**
   * Linking this crate needs all the dependencies available.
   *
   * @return Need all deps.
   */
  public boolean needAllDeps() {
    return this == BIN || isDynamic() || isNative();
  }

  /**
   * Crate dynamically links with its dependents.
   *
   * @return Is dynamic.
   */
  public boolean isDynamic() {
    return this == CDYLIB || this == DYLIB;
  }

  /**
   * Crate needs to be compiled with relocation-model=pic. Executables are currently always compiled
   * with -pie, and so are also PIC.
   *
   * @return Needs PIC.
   */
  public boolean isPic() {
    return isDynamic()
        || this == RLIB_PIC
        || this == STATIC_PIC
        || this == BIN
        || this == PROC_MACRO;
  }

  /** Create generates an executable */
  public boolean isExecutable() {
    return this == BIN;
  }

  /**
   * We're just checking the code, and generating metadata to allow dependents to check. For
   * libraries this means we emit a metadata file, and binaries produce no output (they just consume
   * library metadata).
   */
  public boolean isCheck() {
    return this == CHECK || this == CHECKBIN;
  }

  /**
   * Return true if this is generating a compiler plugin - ie, it should be linked with a different
   * linker and linker flags.
   */
  public boolean isProcMacro() {
    return this == PROC_MACRO;
  }

  /**
   * Returns true if the build is expected to produce output (vs is just being run for
   * error-checking side-effects). The only build which produces no output is a CHECKBIN build
   */
  public boolean hasOutput() {
    return this != CHECKBIN;
  }

  /**
   * Return an appropriate filename for this crate, given its type and the platform.
   *
   * @param name Base filename
   * @param cxxPlatform Platform we're building for
   * @return Path component
   */
  public String filenameFor(BuildTarget target, String name, CxxPlatform cxxPlatform) {
    return filenameMap.apply(target, name, cxxPlatform);
  }
}
