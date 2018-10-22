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

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Subclass of WindowsCompiler with overrides specific for clang-cl. */
public class ClangClCompiler extends WindowsCompiler {

  public ClangClCompiler(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<String> getFlagsForReproducibleBuild(
      String altCompilationDir, Path currentCellPath) {
    return ImmutableList.of(
        "/Brepro", "-Xclang", "-fdebug-compilation-dir", "-Xclang", altCompilationDir);
  }

  @Override
  public Optional<String> getStderr(ProcessExecutor.Result result) {
    // clang-cl is sensible
    // But also insensible. It emits /showIncludes output to stdout. We need to combine
    // the streams to get anything reasonable to work, including depfiles.
    Optional<String> stdout = result.getStdout();
    Optional<String> stderr = result.getStderr();
    if (stdout.isPresent()) {
      if (stderr.isPresent()) {
        return Optional.of(stdout.get() + stderr.get());
      }
      return stdout;
    }
    // Either !isPresent or has a value, either way it's the only right answer.
    return stderr;
  }
}
