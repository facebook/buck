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
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

/** Preprocessor implementation for the Clang toolchain. */
public class ClangPreprocessor extends DelegatingTool implements Preprocessor {

  public ClangPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  public boolean supportsHeaderMaps() {
    return true;
  }

  @Override
  public boolean supportsPrecompiledHeaders() {
    return true;
  }

  @Override
  public final Iterable<String> localIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-I"),
        Iterables.transform(includeRoots, MorePaths::pathWithUnixSeparators));
  }

  @Override
  public final Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-isystem"),
        Iterables.transform(includeRoots, MorePaths::pathWithUnixSeparators));
  }

  @Override
  public final Iterable<String> prefixHeaderArgs(Path prefixHeader) {
    Preconditions.checkArgument(
        !prefixHeader.toString().endsWith(".gch"),
        "Expected non-precompiled file, got a '.gch': " + prefixHeader);
    return ImmutableList.of("-include", MorePaths.pathWithUnixSeparators(prefixHeader));
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    Preconditions.checkArgument(
        pchOutputPath.toString().endsWith(".h.gch"),
        "Expected a precompiled '.gch' file, got: " + pchOutputPath);
    return ImmutableList.of(
        "-include-pch",
        MorePaths.pathWithUnixSeparators(pchOutputPath),
        // Force clang to accept pch even if mtime of its input changes, since buck tracks
        // input contents, this should be safe.
        "-Wp,-fno-validate-pch");
  }

  /** @returns whether this tool requires Unix path separated paths. */
  public boolean getUseUnixPathSeparator() {
    return true;
  }
}
