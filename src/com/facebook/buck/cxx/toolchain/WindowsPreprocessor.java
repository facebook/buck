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

import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Preprocessor implementation for the Windows toolchain. */
public class WindowsPreprocessor extends DelegatingTool implements Preprocessor {

  public static final List<String> ENABLE_WIN_EXTERNAL_FLAGS =
      Collections.singletonList("/experimental:external");
  public static final String WIN_SYSTEM_INCLUDE_FLAG = "/external:I";

  public WindowsPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  public boolean supportsHeaderMaps() {
    return false;
  }

  @Override
  public boolean supportsPrecompiledHeaders() {
    // TODO(steveo) Should be easy to add support; will try @ later time,
    // when I can test w/ Windows.
    // https://msdn.microsoft.com/en-us/library/z0atkd6c.aspx
    return false;
  }

  private static String prependIncludeFlag(String includeRoot) {
    return "/I" + includeRoot;
  }

  private static String prependSystemIncludeFlag(String includeRoot) {
    return WIN_SYSTEM_INCLUDE_FLAG + includeRoot;
  }

  @Override
  public Iterable<String> localIncludeArgs(Iterable<String> includeRoots) {
    return Iterables.transform(includeRoots, WindowsPreprocessor::prependIncludeFlag);
  }

  @Override
  public Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    if (Iterables.isEmpty(includeRoots)) {
      return Collections.emptyList();
    }

    return Iterables.concat(
        ENABLE_WIN_EXTERNAL_FLAGS,
        Iterables.transform(includeRoots, WindowsPreprocessor::prependSystemIncludeFlag));
  }

  @Override
  public Iterable<String> prefixHeaderArgs(Path prefixHeader) {
    throw new UnsupportedOperationException("prefix header not supported by " + getClass());
    // TODO(steveo) Should be easy to add support; will try @ later time,
    // when I can test w/ Windows.
    // "Forced Include": https://msdn.microsoft.com/en-us/library/8c5ztk84.aspx
    // Space is allowed between flag and its pathname argument.
    // E.g. something like this (space allowed between flag and its argument):
    // return ImmutableList.of("/FI", resolver.getAbsolutePath(prefixHeader).toString());
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    throw new UnsupportedOperationException("precompiled header not supported by " + getClass());
    // TODO(steveo) Should be easy to add support; will try @ later time,
    // when I can test w/ Windows.
    // https://msdn.microsoft.com/en-us/library/z0atkd6c.aspx
    // E.g. something like this flag (no space between "/Yu" and its argument):
    // return ImmutableList.of("/Yu" + pchOutputPath);
  }
}
