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

package com.facebook.buck.cxx.toolchain.nativelink;

/**
 * The ways that other language (e.g. Python, Lua) binaries pull in native linkable dependencies.
 */
public enum NativeLinkStrategy {

  /**
   * Pull transitive native deps in as fully linked standalone shared libraries. This is typically
   * the fastest build-time link strategy, as it requires no top-level context and therefore can
   * shared build artifacts with all other binaries using this strategy.
   */
  SEPARATE,

  /**
   * Statically link all transitive native deps, which don't have an explicit dep from non-C/C++
   * code (e.g. Python), into a monolithic shared library. Native dep roots, which have an explicit
   * dep from non-C/C++ code, remain as fully linked standalone shared libraries so that, typically,
   * application code doesn't need to change to work with this strategy. This strategy incurs a
   * relatively big build-time cost, but can significantly reduce the size of native code and number
   * of shared libraries pulled into the binary.
   */
  MERGED,
}
