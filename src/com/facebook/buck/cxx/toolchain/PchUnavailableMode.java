/*
 * Copyright 2013-present Facebook, Inc.
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

/**
 * Use with {@code cxx.precompiling_unavailable=(mode)} to handle cases where the target graph has a
 * rule with {@code precompiled_header=":rule"} but PCHs are disabled via config or unavailable in
 * this toolchain. {@code error} is the default.
 */
public enum PchUnavailableMode {
  /** Raise {@link PchUnavailableException} if a PCH is attempted but unavailable. */
  ERROR,

  /**
   * Print a warning but proceed anyway. Precompiled headers won't be used. If the PCH is needed to
   * properly build, e.g. the source using the PCH doesn't do {@code #include}s for needed headers,
   * the build will fail with a compiler error.
   */
  WARN,

  /*
   * TODO(steveo): add a `USE_UNCOMPILED` mode which will simply include the header
   * which was to be precompiled as a prefix header instead, e.g. with `-include`, instead of
   * `-include-pch`, and have the preprocessor generate the appropriate args.
   */
}
