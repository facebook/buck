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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableMap;

/**
 * Slightly misnamed. Really just a non-source input to the compiler (ie, an already-compiled Rust
 * crate).
 */
interface RustLinkable {
  /**
   * Return Arg for dependency.
   *
   * @param direct true for direct dependency, false for transitive
   * @param isCheck true if we're generated check builds
   * @param cxxPlatform Current platform we're building for.
   * @param depType What kind of linkage we want with the dependency.
   * @return Arg for linking dependency.
   */
  Arg getLinkerArg(
      boolean direct, boolean isCheck, CxxPlatform cxxPlatform, Linker.LinkableDepType depType);

  /**
   * Return {@link BuildTarget} for linkable
   *
   * @return BuildTarget for linkable.
   */
  BuildTarget getBuildTarget();

  /**
   * Return a map of shared libraries this linkable produces (typically just one)
   *
   * @param cxxPlatform the platform we're generating the shared library for
   * @return Map of soname -> source path
   */
  ImmutableMap<String, SourcePath> getRustSharedLibraries(CxxPlatform cxxPlatform);

  /**
   * Return the linkage style for this linkable.
   *
   * @return Linkage mode.
   */
  NativeLinkable.Linkage getPreferredLinkage();

  /** Return true if this is a compiler plugin */
  boolean isProcMacro();
}
