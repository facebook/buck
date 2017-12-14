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

package com.facebook.buck.model.macros;

/** Used by MacroFinder to process a string containing macros. */
public interface MacroCombiner<T> {
  /** Returns the combined result. */
  T build();

  /** Add a non-macro containing substring. */
  void addString(String part);

  /** Add the expanded form of a macro. */
  void add(T expanded);
}
