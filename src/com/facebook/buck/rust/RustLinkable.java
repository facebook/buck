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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.rules.args.Arg;

/**
 * Slightly misnamed. Really just a non-source input to the compiler (ie, an already-compiled
 * Rust crate).
 */
interface RustLinkable {
  /**
   * Return Arg for dependency.
   *
   * @param direct      true for direct dependency, false for transitive
   * @param cxxPlatform Current platform we're building for.
   * @param depType     What kind of linkage we want with the dependency.
   * @return Arg for linking dependency.
   */
  Arg getLinkerArg(boolean direct, CxxPlatform cxxPlatform, Linker.LinkableDepType depType);
}
