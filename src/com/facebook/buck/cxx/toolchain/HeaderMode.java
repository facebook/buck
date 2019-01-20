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

package com.facebook.buck.cxx.toolchain;

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
   * symbolic links to the headers.
   */
  SYMLINK_TREE_WITH_MODULEMAP,
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
}
