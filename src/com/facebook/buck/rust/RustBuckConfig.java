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

package com.facebook.buck.rust;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RustBuckConfig {
  private static final Path DEFAULT_RUSTC_COMPILER = Paths.get("rustc");

  private final BuckConfig delegate;

  public RustBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  Supplier<Tool> getRustCompiler() {
    Path compilerPath = delegate.getPath("rust", "compiler").or(DEFAULT_RUSTC_COMPILER);

    Path compiler = new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());

    return Suppliers.<Tool>ofInstance(new HashedFileTool(compiler));
  }
}
