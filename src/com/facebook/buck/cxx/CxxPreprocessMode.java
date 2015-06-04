/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

public enum CxxPreprocessMode {
  /**
   * Run the preprocessor and compiler separately, treating each intermediate product as a separate
   * Buck object.  From .c files, build .i files using the preprocessor, and from .i files, build .o
   * files.  Rewrite #line directives to normalize debugging information.
   */
  SEPARATE,

  /**
   * Run the preprocessor and compiler separately, but pipe the output of the former into the latter
   * through Buck, which will rewrite #line directives.  Performs better than SEPARATE and does not
   * produce intermediate preprocessed objects.  Rewrite #line directives to normalize
   * debugging information.
   */
  PIPED,

  /**
   * Run the preprocessor and compiler together.  Does not currently normalize debugging
   * information at the preprocessor level; does a search-and-replace on the debug section
   * instead.
   *
   * TODO(user): Figure out a sane way to normalize debugging information without having access to
   * #line directives.  -fremap-prefix-map?  A real DWARF parser?
   */
  COMBINED,
};
