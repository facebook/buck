/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.Iterables;

/** Preprocessor implementation for compilations using clang-cl. */
public class ClangClPreprocessor extends WindowsPreprocessor implements Preprocessor {

  private static final String FORWARD_FLAG_TO_CLANG = "-Xclang";
  private static final String CLANG_SYSTEM_INCLUDE_FLAG = "-isystem";

  public ClangClPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  // Clang-cl doesn't understand '/external:I' flag for system includes, so we have to pass
  // it '-isystem'. Other than that, clang-cl is pretty much drop in compatible with cl settings.
  public Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(
        Iterables.cycle(FORWARD_FLAG_TO_CLANG),
        Iterables.cycle(CLANG_SYSTEM_INCLUDE_FLAG),
        Iterables.cycle(FORWARD_FLAG_TO_CLANG),
        includeRoots);
  }
}
