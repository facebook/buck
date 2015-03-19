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
import com.facebook.buck.cxx.HashedFileTool;
import com.facebook.buck.cxx.Tool;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.file.Files;
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
    // Look up the compiler in the PATH, unless there is a pathSeparator in the name.
    if (!compilerPath.toString().contains(File.separator)) {
      ImmutableList.Builder<Path> paths = ImmutableList.builder();
      for (String path : delegate.getEnv("PATH", File.pathSeparator)) {
        paths.add(Paths.get(path));
      }
      Optional<Path> compiler = MorePaths.searchPathsForExecutable(
          compilerPath,
          paths.build(),
          ImmutableList.copyOf(delegate.getEnv("PATHEXT", File.pathSeparator)));
      if (compiler.isPresent()) {
        compilerPath = compiler.get();
      }
    }

    if (!Files.exists(compilerPath)) {
      throw new HumanReadableException("D compiler " + compilerPath.toString() + " not found");
    } else if (!Files.isExecutable(compilerPath)) {
      throw new HumanReadableException(
          "D compiler " + compilerPath.toString() + " is not executable");
    }

    return new HashedFileTool(compilerPath);
  }
}
