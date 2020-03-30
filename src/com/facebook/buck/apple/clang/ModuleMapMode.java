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

package com.facebook.buck.apple.clang;

/** Enumerates the module map generation modes that Buck supports. */
public enum ModuleMapMode {
  /** Generate a module map with explicit "header" declarations. */
  HEADERS,

  /** Generate a module map that requires an umbrella header. */
  UMBRELLA_HEADER,
  ;

  /**
   * If true, an umbrella header for the module should be created if it is not already present in
   * the user-declared exported header files. Otherwise, no umbrella header should be automatically
   * generated.
   */
  public boolean shouldGenerateMissingUmbrellaHeader() {
    switch (this) {
      case HEADERS:
        return false;
      case UMBRELLA_HEADER:
        return true;
    }
    throw new RuntimeException();
  }
}
