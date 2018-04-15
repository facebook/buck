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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

/** Preprocessor implementation for the Windows toolchain. */
public class WindowsPreprocessor extends DelegatingTool implements Preprocessor {
  public WindowsPreprocessor(Tool tool) {
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

  private static String prependIncludeFlag(String includeRoot) {
    return "/I" + includeRoot;
  }

  @Override
  public Iterable<String> localIncludeArgs(Iterable<String> includeRoots) {
    return Iterables.transform(includeRoots, WindowsPreprocessor::prependIncludeFlag);
  }

  @Override
  public Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return Iterables.transform(includeRoots, WindowsPreprocessor::prependIncludeFlag);
  }

  @Override
  public Iterable<String> quoteIncludeArgs(Iterable<String> includeRoots) {
    return Iterables.transform(includeRoots, WindowsPreprocessor::prependIncludeFlag);
  }

  @Override
  public Iterable<String> prefixHeaderArgs(Path prefixHeader) {
    return ImmutableList.of("/Yc", prefixHeader.toString());
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    String hFilename = pchOutputPath.getFileName().toString();
    String pchFilename = hFilename.substring(0, hFilename.length() - 4) + ".pch";
    return ImmutableList.of("/Yu", pchFilename);
  }
}
