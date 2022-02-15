/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.dex;

/** Options to pass to {@code d8}. */
public enum D8Options {
  /** Specify the {@code --debug} flag. Otherwise --release is specified */
  NO_OPTIMIZE,

  /** Force the dexer to emit jumbo string references */
  FORCE_JUMBO,

  /** Disable java 8 desugaring when running D8 dexing tool. */
  NO_DESUGAR,

  /** Compile an intermediate result intended for later merging */
  INTERMEDIATE,

  /** Don't fill up the primary dex beyond classes that need to be in the primary dex */
  MINIMIZE_PRIMARY_DEX,

  /** Fill up the primary dex as much as possible */
  MAXIMIZE_PRIMARY_DEX,
  ;
}
