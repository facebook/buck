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
import java.util.Optional;

public class RustBuckConfig {
  private static final String SECTION = "rust";
  private static final Path DEFAULT_RUSTC_COMPILER = Paths.get("rustc");
  private static final String RUSTC_FLAGS = "rustc_flags";
  private static final String RUSTC_BINARY_FLAGS = "rustc_binary_flags";
  private static final String RUSTC_LIBRARY_FLAGS = "rustc_library_flags";
  private static final String RUSTC_CHECK_FLAGS = "rustc_check_flags";
  private static final String RUSTC_TEST_FLAGS = "rustc_test_flags";
  private static final String UNFLAVORED_BINARIES = "unflavored_binaries";

  private final BuckConfig delegate;

  public RustBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  ToolProvider getRustCompiler() {
    return delegate
        .getToolProvider(SECTION, "compiler")
        .orElseGet(
            () -> {
              HashedFileTool tool =
                  new HashedFileTool(
                      new ExecutableFinder()
                          .getExecutable(DEFAULT_RUSTC_COMPILER, delegate.getEnvironment()));
              return new ConstantToolProvider(tool);
            });
  }

  /**
   * Get common set of rustc flags. These are used for all rules that invoke rustc.
   *
   * @return List of rustc option flags.
   */
  private ImmutableList<String> getRustCompilerFlags() {
    return delegate.getListWithoutComments(SECTION, RUSTC_FLAGS, ' ');
  }

  /**
   * Get rustc flags for rust_library() rules.
   *
   * @return List of rustc_library_flags, as well as common rustc_flags.
   */
  ImmutableList<String> getRustLibraryFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(getRustCompilerFlags());
    builder.addAll(delegate.getListWithoutComments(SECTION, RUSTC_LIBRARY_FLAGS, ' '));

    return builder.build();
  }

  /**
   * Get rustc flags for rust_binary() rules.
   *
   * @return List of rustc_binary_flags, as well as common rustc_flags.
   */
  ImmutableList<String> getRustBinaryFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(getRustCompilerFlags());
    builder.addAll(delegate.getListWithoutComments(SECTION, RUSTC_BINARY_FLAGS, ' '));

    return builder.build();
  }

  /**
   * Get rustc flags for rust_test() rules.
   *
   * @return List of rustc_test_flags, as well as common rustc_flags.
   */
  ImmutableList<String> getRustTestFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(getRustCompilerFlags());
    builder.addAll(delegate.getListWithoutComments(SECTION, RUSTC_TEST_FLAGS, ' '));

    return builder.build();
  }

  /**
   * Get rustc flags for #check flavored builds. Caller must also include rule-dependent flags and
   * common flags.
   *
   * @return List of rustc_check_flags.
   */
  ImmutableList<String> getRustCheckFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(delegate.getListWithoutComments(SECTION, RUSTC_CHECK_FLAGS, ' '));

    return builder.build();
  }

  Optional<ToolProvider> getLinker() {
    return delegate.getToolProvider(SECTION, "linker");
  }

  LinkerProvider getLinkerProvider(CxxPlatform cxxPlatform, LinkerProvider.Type defaultType) {
    LinkerProvider.Type type =
        delegate.getEnum(SECTION, "linker_platform", LinkerProvider.Type.class).orElse(defaultType);

    return getLinker()
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

  /**
   * Get rustc flags for rust_library() rules.
   *
   * @return List of rustc_library_flags, as well as common rustc_flags.
   */
  boolean getUnflavoredBinaries() {
    return delegate.getBoolean(SECTION, UNFLAVORED_BINARIES).orElse(false);
  }
}
