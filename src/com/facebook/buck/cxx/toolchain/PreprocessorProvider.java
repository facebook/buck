/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.base.Suppliers;
import java.util.Optional;
import java.util.function.Supplier;

public class PreprocessorProvider extends CxxToolProvider<Preprocessor> {
  public PreprocessorProvider(PathSourcePath path, Optional<Type> type) {
    super(Suppliers.ofInstance(path), type, false);
  }

  public PreprocessorProvider(
      PathSourcePath path, Optional<Type> type, boolean useUnixPathSeparator) {
    super(Suppliers.ofInstance(path), type, useUnixPathSeparator);
  }

  public PreprocessorProvider(Supplier<PathSourcePath> path, Optional<Type> type) {
    super(path, type, false);
  }

  public PreprocessorProvider(
      Supplier<PathSourcePath> path, Optional<Type> type, boolean useUnixPathSeparator) {
    super(path, type, useUnixPathSeparator);
  }

  public PreprocessorProvider(ToolProvider toolProvider, Type type) {
    super(toolProvider, type, false);
  }

  public PreprocessorProvider(ToolProvider toolProvider, Type type, boolean useUnixPathSeparator) {
    super(toolProvider, type, useUnixPathSeparator);
  }

  @Override
  protected Preprocessor build(Type type, Tool tool) {
    switch (type) {
      case CLANG:
        return new ClangPreprocessor(tool, getUseUnixPathSeparator());
      case GCC:
        return new GccPreprocessor(tool, getUseUnixPathSeparator());
      case WINDOWS:
        return new WindowsPreprocessor(tool);
      case CLANG_WINDOWS:
        return new ClangWindowsPreprocessor(tool);
        // $CASES-OMITTED$
      default:
    }
    throw new IllegalStateException("cannot get preprocessor for type " + type);
  }
}
