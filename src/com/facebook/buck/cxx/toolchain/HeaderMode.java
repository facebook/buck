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

import com.facebook.buck.apple.clang.ModuleMapMode;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.InternalFlavor;
import com.google.common.base.CaseFormat;

public enum HeaderMode implements FlavorConvertible {

  /** Creates the tree of symbolic links of headers. */
  SYMLINK_TREE_ONLY,
  /** Creates the header map that references the headers directly in the source tree. */
  HEADER_MAP_ONLY,
  /**
   * Creates the tree of symbolic links of headers and creates the header map that references the
   * symbolic links to the headers.
   */
  SYMLINK_TREE_WITH_HEADER_MAP,
  /**
   * Creates the tree of symbolic links of headers and creates a module map that references the
   * symbolic links to the headers. The generated module map will refer to explicit headers.
   */
  SYMLINK_TREE_WITH_HEADERS_MODULEMAP,
  /**
   * Creates the tree of symbolic links of headers and creates a module map that references the
   * symbolic links to the headers. The generated module map will refer to an umbrella header, with
   * the same name as the library.
   */
  SYMLINK_TREE_WITH_UMBRELLA_HEADER_MODULEMAP,
  ;

  private final Flavor flavor;

  HeaderMode() {
    this.flavor =
        InternalFlavor.of(
            String.format(
                "%s-%s",
                CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, getClass().getSimpleName()),
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, toString())));
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  /**
   * Returns the appropriate header mode for module map mode.
   *
   * @param moduleMapMode The module map mode to convert.
   * @return The equivalent header mode.
   */
  public static HeaderMode forModuleMapMode(ModuleMapMode moduleMapMode) {
    switch (moduleMapMode) {
      case HEADERS:
        return SYMLINK_TREE_WITH_HEADERS_MODULEMAP;
      case UMBRELLA_HEADER:
        return SYMLINK_TREE_WITH_UMBRELLA_HEADER_MODULEMAP;
    }

    throw new RuntimeException("Unexpected value of enum ModuleMapMode");
  }

  /**
   * Returns whether or not the header mode will include a module map.
   *
   * @return true if the header mode will include a module map, otherwise false.
   */
  public boolean includesModuleMap() {
    switch (this) {
      case SYMLINK_TREE_ONLY:
      case SYMLINK_TREE_WITH_HEADER_MAP:
      case HEADER_MAP_ONLY:
        return false;
      case SYMLINK_TREE_WITH_HEADERS_MODULEMAP:
      case SYMLINK_TREE_WITH_UMBRELLA_HEADER_MODULEMAP:
        return true;
    }

    throw new RuntimeException("Unexpected value of enum HeaderMode");
  }
}
