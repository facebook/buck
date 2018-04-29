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

package com.facebook.buck.features.rust;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

public class FakeRustConfig extends RustBuckConfig {
  public static final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  public static final FakeRustConfig FAKE_RUST_CONFIG =
      new FakeRustConfig()
          .setCompiler(
              new ConstantToolProvider(
                  new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("/bin/rustc")))))
          .setLinker(
              new DefaultLinkerProvider(
                  LinkerProvider.Type.GNU,
                  new ConstantToolProvider(
                      new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("/bin/rustc"))))));

  private Optional<ToolProvider> compiler = Optional.empty();
  private Optional<ImmutableList<String>> rustcFlags = Optional.empty();
  private Optional<LinkerProvider> linker = Optional.empty();
  private Optional<ImmutableList<String>> linkerFlags = Optional.empty();

  public FakeRustConfig() {
    super(FakeBuckConfig.builder().build());
  }

  FakeRustConfig setCompiler(ToolProvider compiler) {
    this.compiler = Optional.of(compiler);
    return this;
  }

  void setCompilerFlags(ImmutableList<String> flags) {
    this.rustcFlags = Optional.of(flags);
  }

  FakeRustConfig setLinker(LinkerProvider linker) {
    this.linker = Optional.of(linker);
    return this;
  }

  void setLinkerArgs(ImmutableList<String> args) {
    this.linkerFlags = Optional.of(args);
  }
}
