/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Abstracting the tooling/flags/libraries used to build Rust rules. */
@BuckStyleValueWithBuilder
interface RustPlatform extends FlavorConvertible {

  // TODO: For now, we rely on Rust platforms having the same "name" as the C/C++ platforms they
  // wrap, due to having to lookup the Rust platform in the C/C++ interfaces that Rust rules
  // implement, into which only C/C++ platform objects are threaded.
  @Override
  default Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }

  ToolProvider getRustCompiler();

  /**
   * Get rustc flags for rust_library() rules.
   *
   * @return List of rustc_library_flags, as well as common rustc_flags.
   */
  ImmutableList<StringArg> getRustLibraryFlags();

  /**
   * Get rustc flags for rust_binary() rules.
   *
   * @return List of rustc_binary_flags, as well as common rustc_flags.
   */
  ImmutableList<StringArg> getRustBinaryFlags();

  /**
   * Get rustc flags for rust_test() rules.
   *
   * @return List of rustc_test_flags, as well as common rustc_flags.
   */
  ImmutableList<StringArg> getRustTestFlags();

  /**
   * Get rustc flags for #check flavored builds. Caller must also include rule-dependent flags and
   * common flags.
   *
   * @return List of rustc_check_flags.
   */
  ImmutableList<StringArg> getRustCheckFlags();

  Optional<ToolProvider> getLinker();

  LinkerProvider getLinkerProvider();

  // Get args for linker. Always return rust.linker_args if provided, and also include cxx.ldflags
  // if we're using the Cxx platform linker.
  ImmutableList<Arg> getLinkerArgs();

  /** @return the {@link CxxPlatform} to use for C/C++ dependencies. */
  CxxPlatform getCxxPlatform();

  Optional<Path> getXcrunSdkPath();
}
