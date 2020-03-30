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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

/** Interface describing a C/C++ toolchain and platform to build for. */
@BuckStyleValueWithBuilder
public interface CxxPlatform extends FlavorConvertible {

  @Override
  Flavor getFlavor();

  CompilerProvider getAs();

  ImmutableList<Arg> getAsflags();

  PreprocessorProvider getAspp();

  ImmutableList<Arg> getAsppflags();

  CompilerProvider getCc();

  ImmutableList<Arg> getCflags();

  CompilerProvider getCxx();

  ImmutableList<Arg> getCxxflags();

  PreprocessorProvider getCpp();

  ImmutableList<Arg> getCppflags();

  PreprocessorProvider getCxxpp();

  ImmutableList<Arg> getCxxppflags();

  Optional<PreprocessorProvider> getCudapp();

  ImmutableList<Arg> getCudappflags();

  Optional<CompilerProvider> getCuda();

  ImmutableList<Arg> getCudaflags();

  // HIP is the compiler for AMD rocm tool chain
  Optional<PreprocessorProvider> getHippp();

  ImmutableList<Arg> getHipppflags();

  Optional<CompilerProvider> getHip();

  ImmutableList<Arg> getHipflags();

  Optional<PreprocessorProvider> getAsmpp();

  ImmutableList<Arg> getAsmppflags();

  Optional<CompilerProvider> getAsm();

  ImmutableList<Arg> getAsmflags();

  LinkerProvider getLd();

  ImmutableList<Arg> getLdflags();

  ImmutableMultimap<Linker.LinkableDepType, Arg> getRuntimeLdflags();

  Tool getStrip();

  ImmutableList<Arg> getStripFlags();

  ArchiverProvider getAr();

  ArchiveContents getArchiveContents();

  ImmutableList<Arg> getArflags();

  Optional<ToolProvider> getRanlib();

  ImmutableList<Arg> getRanlibflags();

  SymbolNameTool getSymbolNameTool();

  String getSharedLibraryExtension();

  String getSharedLibraryVersionedExtensionFormat();

  String getStaticLibraryExtension();

  String getObjectFileExtension();

  DebugPathSanitizer getCompilerDebugPathSanitizer();

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

  /** @return whether shorter names for intermediate files should be used */
  @Value.Default
  default boolean getFilepathLengthLimited() {
    return false;
  }

  static Builder builder() {
    return new Builder();
  }

  default CxxPlatform withFlavor(Flavor flavor) {
    if (getFlavor().equals(flavor)) {
      return this;
    }
    return builder().from(this).setFlavor(flavor).build();
  }

  default CxxPlatform withAsflags(ImmutableList<Arg> asFlags) {
    if (getAsflags().equals(asFlags)) {
      return this;
    }
    return builder().from(this).setAsflags(asFlags).build();
  }

  default CxxPlatform withCppflags(ImmutableList<Arg> cppFlags) {
    if (getCppflags().equals(cppFlags)) {
      return this;
    }
    return builder().from(this).setCppflags(cppFlags).build();
  }

  default CxxPlatform withCflags(ImmutableList<Arg> cFlags) {
    if (getCflags().equals(cFlags)) {
      return this;
    }
    return builder().from(this).setCflags(cFlags).build();
  }

  default CxxPlatform withCpp(PreprocessorProvider cpp) {
    if (getCpp().equals(cpp)) {
      return this;
    }
    return builder().from(this).setCpp(cpp).build();
  }

  default CxxPlatform withConflictingHeaderBasenameWhitelist(
      ImmutableSortedSet<String> conflictingHeaderBasenameWhitelist) {
    if (getConflictingHeaderBasenameWhitelist().equals(conflictingHeaderBasenameWhitelist)) {
      return this;
    }
    return builder()
        .from(this)
        .setConflictingHeaderBasenameWhitelist(conflictingHeaderBasenameWhitelist)
        .build();
  }

  default CxxPlatform withCxxppflags(ImmutableList<Arg> cxxppflags) {
    if (getCxxppflags().equals(cxxppflags)) {
      return this;
    }
    return builder().from(this).setCxxppflags(cxxppflags).build();
  }

  default CxxPlatform withCxxflags(ImmutableList<Arg> cxxFlags) {
    if (getCxxflags().equals(cxxFlags)) {
      return this;
    }
    return builder().from(this).setCxxflags(cxxFlags).build();
  }

  default CxxPlatform withArchiveContents(ArchiveContents archiveContents) {
    if (getArchiveContents().equals(archiveContents)) {
      return this;
    }
    return builder().from(this).setArchiveContents(archiveContents).build();
  }

  default CxxPlatform withFlagMacros(ImmutableMap<String, String> flagMacros) {
    if (getFlagMacros().equals(flagMacros)) {
      return this;
    }
    return builder().from(this).setFlagMacros(flagMacros).build();
  }

  default CxxPlatform withCompilerDebugPathSanitizer(
      DebugPathSanitizer compilerDebugPathSanitizer) {
    if (getCompilerDebugPathSanitizer().equals(compilerDebugPathSanitizer)) {
      return this;
    }
    return builder().from(this).setCompilerDebugPathSanitizer(compilerDebugPathSanitizer).build();
  }

  default CxxPlatform withCxxpp(PreprocessorProvider preprocessorProvider) {
    if (getCxxpp().equals(preprocessorProvider)) {
      return this;
    }
    return builder().from(this).setCxxpp(preprocessorProvider).build();
  }

  default CxxPlatform withCxx(CompilerProvider compilerProvider) {
    if (getCxx().equals(compilerProvider)) {
      return this;
    }
    return builder().from(this).setCxx(compilerProvider).build();
  }

  class Builder extends ImmutableCxxPlatform.Builder {}
}
