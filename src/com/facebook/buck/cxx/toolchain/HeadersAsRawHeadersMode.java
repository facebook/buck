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

package com.facebook.buck.cxx.toolchain;

/**
 * Modes supporting implementing the `headers` parameter of C++ rules using raw headers instead of
 * e.g. symlink trees.
 */
public enum HeadersAsRawHeadersMode {
  /** Require that all headers be implemented as raw headers, failing if this is not possible. */
  REQUIRED,

  /**
   * Attempt to imlpement headers via raw headers, falling to header maps or symlink tress when raw
   * headers cannot be used (e.g. rule contains a generated header or remaps a header to an
   * incompatible location in the header namespace).
   */
  PREFERRED,

  DISABLED,
}
