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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import java.nio.file.Path;
import java.nio.file.Paths;

public class KotlinBuckConfig {
  private static final Path DEFAULT_KOTLIN_COMPILER = Paths.get("kotlinc");

  private final BuckConfig delegate;

  public KotlinBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Supplier<Tool> getKotlinCompiler() {
    Path compilerPath = delegate.getPath("kotlin", "compiler").orElse(DEFAULT_KOTLIN_COMPILER);

    Path compiler = new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());

    return Suppliers.ofInstance(new HashedFileTool(compiler));
  }
}
