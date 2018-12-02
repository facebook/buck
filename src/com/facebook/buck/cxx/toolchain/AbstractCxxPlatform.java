/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

/** Interface describing a C/C++ toolchain and platform to build for. */
@Value.Immutable(copy = true)
@BuckStyleImmutable
interface AbstractCxxPlatform extends FlavorConvertible {

  @Override
  Flavor getFlavor();

  CompilerProvider getAs();

  List<String> getAsflags();

  PreprocessorProvider getAspp();

  List<String> getAsppflags();

  CompilerProvider getCc();

  List<String> getCflags();

  CompilerProvider getCxx();

  List<String> getCxxflags();

  PreprocessorProvider getCpp();

  List<String> getCppflags();

  PreprocessorProvider getCxxpp();

  List<String> getCxxppflags();

  Optional<PreprocessorProvider> getCudapp();

  List<String> getCudappflags();

  Optional<CompilerProvider> getCuda();

  List<String> getCudaflags();

  // HIP is the compiler for AMD rocm tool chain
  Optional<PreprocessorProvider> getHippp();

  List<String> getHipppflags();

  Optional<CompilerProvider> getHip();

  List<String> getHipflags();

  Optional<PreprocessorProvider> getAsmpp();

  List<String> getAsmppflags();

  Optional<CompilerProvider> getAsm();

  List<String> getAsmflags();

  LinkerProvider getLd();

  List<String> getLdflags();

  Multimap<Linker.LinkableDepType, String> getRuntimeLdflags();

  Tool getStrip();

  List<String> getStripFlags();

  ArchiverProvider getAr();

  List<String> getArflags();

  Optional<ToolProvider> getRanlib();

  List<String> getRanlibflags();

  SymbolNameTool getSymbolNameTool();

  String getSharedLibraryExtension();

  String getSharedLibraryVersionedExtensionFormat();

  String getStaticLibraryExtension();

  String getObjectFileExtension();

  DebugPathSanitizer getCompilerDebugPathSanitizer();

  DebugPathSanitizer getAssemblerDebugPathSanitizer();

  HeaderVerification getHeaderVerification();

  Optional<Boolean> getUseArgFile();

  /**
   * @return a map for macro names to their respective expansions, to be used to expand macro
   *     references in user-provided flags.
   */
  ImmutableMap<String, String> getFlagMacros();

  /**
   * @return params used to determine which shared library interfaces to generate, which are used
   *     for linking in liu of the original shared library.
   */
  Optional<SharedLibraryInterfaceParams> getSharedLibraryInterfaceParams();

  Optional<String> getBinaryExtension();

  /**
   * When building or creating a project, create symlinks for the public headers if it's true. It
   * would allow public headers to include an other public header with #include "foobar.h"\ even if
   * it's not in the same folder.
   */
  boolean getPublicHeadersSymlinksEnabled();

  /** When building or creating a project, create symlinks for the public headers if it's true. */
  boolean getPrivateHeadersSymlinksEnabled();

  /** *nix platforms use PIC object files for shared libraries, while windows doesn't. */
  @Value.Default
  default PicType getPicTypeForSharedLinking() {
    return PicType.PIC;
  }

  /** @return whitelist of basenames to exclude from conflicting header checks. */
  @Value.Default
  default ImmutableSortedSet<String> getConflictingHeaderBasenameWhitelist() {
    return ImmutableSortedSet.of();
  }

  /** @return the explicit header mode to use for this platform. */
  Optional<HeaderMode> getHeaderMode();
}
