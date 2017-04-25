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

package com.facebook.buck.rust;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.ToolProvider;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;

public class FakeRustConfig extends RustBuckConfig {

  public static final FakeRustConfig FAKE_RUST_CONFIG =
      new FakeRustConfig()
          .setCompiler(new ConstantToolProvider(new HashedFileTool(Paths.get("/bin/rustc"))))
          .setLinker(
              new DefaultLinkerProvider(
                  LinkerProvider.Type.GNU,
                  new ConstantToolProvider(new HashedFileTool(Paths.get("/bin/rustc")))));

  private Optional<ToolProvider> compiler = Optional.empty();
  private Optional<ImmutableList<String>> rustcFlags = Optional.empty();
  private Optional<LinkerProvider> linker = Optional.empty();
  private Optional<ImmutableList<String>> linkerFlags = Optional.empty();

  public FakeRustConfig() {
    super(FakeBuckConfig.builder().build());
  }

  @Override
  ToolProvider getRustCompiler() {
    return compiler.orElseGet(super::getRustCompiler);
  }

  @Override
  ImmutableList<String> getRustLibraryFlags() {
    return rustcFlags.orElseGet(super::getRustLibraryFlags);
  }

  @Override
  ImmutableList<String> getRustBinaryFlags() {
    return rustcFlags.orElseGet(super::getRustBinaryFlags);
  }

  @Override
  LinkerProvider getLinkerProvider(CxxPlatform cxxPlatform, LinkerProvider.Type defaultType) {
    return linker.orElseGet(() -> super.getLinkerProvider(cxxPlatform, defaultType));
  }

  @Override
  ImmutableList<String> getLinkerArgs(CxxPlatform cxxPlatform) {
    return linkerFlags.orElse(super.getLinkerArgs(cxxPlatform));
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
