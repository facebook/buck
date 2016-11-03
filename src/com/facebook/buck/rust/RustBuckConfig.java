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
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.ToolProvider;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RustBuckConfig {
  private static final String SECTION = "rust";
  private static final Path DEFAULT_RUSTC_COMPILER = Paths.get("rustc");

  private final BuckConfig delegate;

  public RustBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  ToolProvider getRustCompiler() {
    return delegate
        .getToolProvider(SECTION, "compiler")
        .orElseGet(
            () -> {
              HashedFileTool tool = new HashedFileTool(
                  new ExecutableFinder().getExecutable(
                      DEFAULT_RUSTC_COMPILER,
                      delegate.getEnvironment()));
              return new ConstantToolProvider(tool);
            });
  }

  ImmutableList<String> getRustCompilerFlags() {
    return delegate.getListWithoutComments(SECTION, "rustc_flags", ' ');
  }

  LinkerProvider getLinkerProvider(
      CxxPlatform cxxPlatform,
      LinkerProvider.Type defaultType) {
    LinkerProvider.Type type =
        delegate.getEnum(SECTION, "linker_platform", LinkerProvider.Type.class)
            .orElse(defaultType);

    return delegate.getToolProvider(SECTION, "linker")
            .map(tp -> (LinkerProvider) new DefaultLinkerProvider(type, tp))
            .orElseGet(cxxPlatform::getLd);
  }

  // Get args for linker. Always return rust.linker_args if provided, and also include cxx.ldflags
  // if we're using the Cxx platform linker.
  ImmutableList<String> getLinkerArgs(CxxPlatform cxxPlatform) {
    ImmutableList.Builder<String> linkargs = ImmutableList.builder();

    linkargs.addAll(delegate.getListWithoutComments(SECTION, "linker_args"));

    if (!delegate.getPath(SECTION, "linker").isPresent()) {
      linkargs.addAll(cxxPlatform.getLdflags());
    }

    return linkargs.build();
  }
}
