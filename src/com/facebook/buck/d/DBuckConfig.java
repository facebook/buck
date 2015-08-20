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

package com.facebook.buck.d;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.io.ExecutableFinder;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DBuckConfig {
  private static final Path DEFAULT_D_COMPILER = Paths.get("dmd");

  private final BuckConfig delegate;

  public DBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  Tool getDCompiler() {
    Path compilerPath = delegate.getPath("d", "compiler").or(DEFAULT_D_COMPILER);

    Path compiler = new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());

    return new HashedFileTool(compiler);
  }
}
