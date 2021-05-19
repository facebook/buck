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

package net.starlark.java.annot;

/** Function purity level. */
public enum FnPurity {
  /**
   * Function is safe to call speculatively (i. e. at compile time).
   *
   * <p>Example functions: {@code tuple}, {@code len}.
   */
  SPEC_SAFE,
  /**
   * A function is pure if it does not modify global state.
   *
   * <p>Example functions: {@code list}, {@code dict}: it is pointless to call them speculatively
   * (because they return mutable value), but they can still be used in pure code.
   *
   * <p>Functions like {@code list.append} are also considered pure.
   */
  PURE,
  /** Function is not pure. */
  DEFAULT,
  ;
}
