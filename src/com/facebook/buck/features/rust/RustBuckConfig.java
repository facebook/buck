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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.util.Optional;
import java.util.function.Supplier;

public class RustBuckConfig {

  private static final String SECTION = "rust";
  private static final String RUSTC_FLAGS = "rustc_flags";
  private static final String RUSTC_BINARY_FLAGS = "rustc_binary_flags";
  private static final String RUSTC_LIBRARY_FLAGS = "rustc_library_flags";
  private static final String RUSTC_CHECK_FLAGS = "rustc_check_flags";
  private static final String RUSTC_TEST_FLAGS = "rustc_test_flags";
  private static final String UNFLAVORED_BINARIES = "unflavored_binaries";
  private static final String REMAP_SRC_PATHS = "remap_src_paths";
  private static final String FORCE_RLIB = "force_rlib";
  private static final String PREFER_STATIC_LIBS = "prefer_static_libs";

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

  private <T> Optional<T> firstOf(Supplier<Optional<T>> first, Supplier<Optional<T>> second) {
    for (Supplier<Optional<T>> optional : ImmutableList.of(first, second)) {
      Optional<T> val = optional.get();
      if (val.isPresent()) {
        return val;
      }
    }
    return Optional.empty();
  }

  private ImmutableList<String> getFlags(String platform, String field, char delim) {
    return delegate.getValue(SECTION + '#' + platform, field).isPresent()
        ? delegate.getListWithoutComments(SECTION + '#' + platform, field, delim)
        : delegate.getListWithoutComments(SECTION, field, delim);
  }

  private ImmutableList<String> getCompilerFlags(String platform, String field) {
    return getFlags(platform, field, ' ');
  }

  private Optional<ToolProvider> getRustTool(String platform, String field) {
    return firstOf(
        () -> delegate.getView(ToolConfig.class).getToolProvider(SECTION + '#' + platform, field),
        () -> delegate.getView(ToolConfig.class).getToolProvider(SECTION, field));
  }

  public Optional<ToolProvider> getRustCompiler(String platform) {
    return getRustTool(platform, "compiler");
  }

  /**
   * Get common set of rustc flags. These are used for all rules that invoke rustc.
   *
   * @return List of rustc option flags.
   */
  private ImmutableList<String> getRustCompilerFlags(String platform) {
    return getCompilerFlags(platform, RUSTC_FLAGS);
  }

  /**
   * Get rustc flags for rust_library() rules.
   *
   * @return List of rustc_library_flags, as well as common rustc_flags.
   */
  public ImmutableList<String> getRustcLibraryFlags(String platform) {
    return ImmutableList.<String>builder()
        .addAll(getRustCompilerFlags(platform))
        .addAll(getCompilerFlags(platform, RUSTC_LIBRARY_FLAGS))
        .build();
  }

  /**
   * Get rustc flags for rust_binary() rules.
   *
   * @return List of rustc_binary_flags, as well as common rustc_flags.
   */
  public ImmutableList<String> getRustcBinaryFlags(String platform) {
    return ImmutableList.<String>builder()
        .addAll(getRustCompilerFlags(platform))
        .addAll(getCompilerFlags(platform, RUSTC_BINARY_FLAGS))
        .build();
  }

  /**
   * Get rustc flags for rust_test() rules.
   *
   * @return List of rustc_test_flags, as well as common rustc_flags.
   */
  public ImmutableList<String> getRustcTestFlags(String platform) {
    return ImmutableList.<String>builder()
        .addAll(getRustCompilerFlags(platform))
        .addAll(getCompilerFlags(platform, RUSTC_TEST_FLAGS))
        .build();
  }

  /**
   * Get rustc flags for #check flavored builds. Caller must also include rule-dependent flags and
   * common flags.
   *
   * @return List of rustc_check_flags.
   */
  public ImmutableList<String> getRustcCheckFlags(String platform) {
    return ImmutableList.<String>builder()
        .addAll(getRustCompilerFlags(platform))
        .addAll(getCompilerFlags(platform, RUSTC_CHECK_FLAGS))
        .build();
  }

  public Optional<ToolProvider> getRustLinker(String platform) {
    return getRustTool(platform, "linker");
  }

  public Optional<LinkerProvider.Type> getLinkerPlatform(String platform) {
    return firstOf(
        () ->
            delegate.getEnum(
                SECTION + '#' + platform, "linker_platform", LinkerProvider.Type.class),
        () -> delegate.getEnum(SECTION, "linker_platform", LinkerProvider.Type.class));
  }

  public ImmutableList<String> getLinkerFlags(String platform) {
    return getFlags(platform, "linker_args", ',');
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
   * Get "force_rlib" config. When set, always generate rlib (static) libraries, even for otherwise
   * shared targets.
   *
   * @return force_rlib flag
   */
  boolean getForceRlib() {
    return delegate.getBooleanValue(SECTION, FORCE_RLIB, false);
  }

  /**
   * Get "prefer_static_libs" config. When set, always use rlib (static) libraries, even for
   * otherwise shared targets. This primarily affects whether to use static or shared standard
   * libraries.
   *
   * @return prefer_static_libs flag
   */
  boolean getPreferStaticLibs() {
    return delegate.getBooleanValue(SECTION, PREFER_STATIC_LIBS, false);
  }
}
