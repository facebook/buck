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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
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
  private static final String REMAP_SRC_PATHS = "remap_src_paths";
  private static final String FORCE_RLIB = "force_rlib";

  enum RemapSrcPaths {
    NO, // no path remapping
    UNSTABLE, // remap using unstable command-line option
    YES, // remap using stable command-line option
    ;

    public void addRemapOption(Builder<String> cmd, String cwd, String basedir) {
      switch (this) {
        case NO:
          break;
        case UNSTABLE:
          cmd.add("-Zremap-path-prefix-from=" + basedir);
          cmd.add("-Zremap-path-prefix-to=");
          cmd.add("-Zremap-path-prefix-from=" + cwd);
          cmd.add("-Zremap-path-prefix-to=./");
          break;
        case YES:
          cmd.add("--remap-path-prefix", basedir + "=");
          cmd.add("--remap-path-prefix", cwd + "=./");
          break;
        default:
          throw new RuntimeException("addRemapOption() not implemented for " + this);
      }
    }
  }

  private final BuckConfig delegate;

  public RustBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  ToolProvider getRustCompiler() {
    return delegate
        .getView(ToolConfig.class)
        .getToolProvider(SECTION, "compiler")
        .orElseGet(
            () -> {
              HashedFileTool tool =
                  new HashedFileTool(
                      delegate.getPathSourcePath(
                          new ExecutableFinder()
                              .getExecutable(DEFAULT_RUSTC_COMPILER, delegate.getEnvironment())));
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
    return delegate.getView(ToolConfig.class).getToolProvider(SECTION, "linker");
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
   * Get unflavored_binaries option. This controls whether executables have the build flavor in
   * their path. This is useful for making the path more deterministic (though really external tools
   * should be asking what the path is).
   *
   * @return Boolean of whether to use unflavored paths.
   */
  boolean getUnflavoredBinaries() {
    return delegate.getBoolean(SECTION, UNFLAVORED_BINARIES).orElse(false);
  }

  /**
   * Get source path remapping option. This controls whether we ask rustc to remap source paths in
   * all output (ie, compiler messages, file!() macros, debug info, etc).
   *
   * @return Remapping mode
   */
  RemapSrcPaths getRemapSrcPaths() {
    return delegate.getEnum(SECTION, REMAP_SRC_PATHS, RemapSrcPaths.class).orElse(RemapSrcPaths.NO);
  }

  /**
   * Get "force_rlib" config. When set, always use rlib (static) libraries, even for otherwise
   * shared targets.
   *
   * @return force_rlib flag
   */
  boolean getForceRlib() {
    return delegate.getBooleanValue(SECTION, FORCE_RLIB, false);
  }
}
