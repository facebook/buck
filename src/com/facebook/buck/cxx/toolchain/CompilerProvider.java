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

public class CompilerProvider extends CxxToolProvider<Compiler> {

  public CompilerProvider(ToolProvider toolProvider, Type type) {
    super(toolProvider, type);
  }

  public CompilerProvider(Supplier<PathSourcePath> path, Optional<Type> type) {
    super(path, type);
  }

  public CompilerProvider(PathSourcePath path, Optional<Type> type) {
    super(Suppliers.ofInstance(path), type);
  }

  @Override
  protected Compiler build(CxxToolProvider.Type type, Tool tool) {
    switch (type) {
      case CLANG:
        return new ClangCompiler(tool);
      case CLANG_CL:
        return new ClangClCompiler(tool);
      case CLANG_WINDOWS:
        return new ClangWindowsCompiler(tool);
      case GCC:
        return new GccCompiler(tool);
      case WINDOWS:
        return new WindowsCompiler(tool);
      case WINDOWS_ML64:
        return new WindowsMl64Compiler(tool);
    }
    throw new IllegalStateException();
  }
}
