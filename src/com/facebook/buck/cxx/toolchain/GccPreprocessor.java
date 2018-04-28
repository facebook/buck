/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

/** Preprocessor implementation for a gcc toolchain. */
public class GccPreprocessor extends DelegatingTool implements Preprocessor {

  public GccPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  public boolean supportsHeaderMaps() {
    return false;
  }

  @Override
  public boolean supportsPrecompiledHeaders() {
    return true;
  }

  @Override
  public final Iterable<String> localIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-I"), includeRoots);
  }

  @Override
  public final Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-isystem"), includeRoots);
  }

  @Override
  public final Iterable<String> quoteIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-iquote"), includeRoots);
  }

  @Override
  public final Iterable<String> prefixHeaderArgs(Path prefixHeader) {
    Preconditions.checkArgument(
        !prefixHeader.toString().endsWith(".gch"),
        "Expected non-precompiled file, got a '.gch': " + prefixHeader);
    return ImmutableList.of("-include", prefixHeader.toString());
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    // Tell GCC "-include file.h"; it'll automatically find the already-precompiled "file.h.gch".
    String pchFilename = pchOutputPath.toString();
    Preconditions.checkArgument(
        pchFilename.endsWith(".h.gch"), "Expected a precompiled '.gch' file, got: " + pchFilename);
    String hFilename = pchFilename.substring(0, pchFilename.length() - 4);
    return ImmutableList.of("-include", hFilename);
  }
}
