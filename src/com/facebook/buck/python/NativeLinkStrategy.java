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

package com.facebook.buck.python;

/**
 * The ways that Python binaries pull in native linkable dependencies.
 */
enum NativeLinkStrategy {

  /**
   * Pull transitive native deps in as fully linked standalone shared libraries.  This is typically
   * the fastest build-time link strategy, as it requires no top-level context and therefore can
   * shared build artifacts with all other binaries using this strategy.
   */
  SPEARATE,

  /**
   * Statically link all transitive native deps, which don't have an explicit dep from python code,
   * into a monolithic shared library.  Native dep roots, which have an explicit dep from python
   * code, remain as fully linked standalone shared libraries so that, typically, application code
   * doesn't need to change to work with this strategy.  This strategy incurs a relatively big
   * build-time cost, but can significantly reduce the size of native code and number of shared
   * libraries pulled into the binary.
   */
  MERGED,

}
