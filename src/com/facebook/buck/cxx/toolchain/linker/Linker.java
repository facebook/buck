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

package com.facebook.buck.cxx.toolchain.linker;

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * An object wrapping a linker, providing its source path and an interface to decorate arguments
 * with specific flags.
 */
public interface Linker extends Tool {

  /**
   * @param cellRootMap Replacement map for cell roots found in paths, to some suitably normalized
   *     form (such as a relative path from root cell).
   */
  ImmutableList<FileScrubber> getScrubbers(ImmutableMap<Path, Path> cellRootMap);

  /**
   * @return the platform-specific way to specify that the library represented by the given argument
   *     should be linked whole.
   */
  Iterable<Arg> linkWhole(Arg input);

  /**
   * @return the platform-specific way to specify that linker should use the given soname when
   *     linking a shared library.
   */
  Iterable<String> soname(String soname);

  /**
   * Specifies that the linker should link the files listed in file. This is an alternative to
   * listing the files on the command line. The file names are listed one per line separated only by
   * newlines. Spaces and tabs are assumed to be part of the file name.
   *
   * @param fileListPath the path to file which contains contents for file list to link
   * @return the platform-specific way to support the feature. Empty list if feature is not
   *     supported.
   */
  Iterable<Arg> fileList(Path fileListPath);

  /**
   * @return the placeholder used by the dynamic loader for the directory containing the top-level
   *     executable.
   */
  String origin();

  /**
   * @return the placeholder used by the dynamic loader for the directory containing the top-level
   *     library (useful for e.g. plugins).
   */
  String libOrigin();

  /** @return the name of the environment variable for the shared library runtime search path. */
  String searchPathEnvVar();

  /** @return the name of the environment variable for the shared library preload search path. */
  String preloadEnvVar();

  /**
   * @return arguments to pass to the linker to disable dropping runtime references to shared libs
   *     which do not resolve symbols as link-time.
   */
  Iterable<String> getNoAsNeededSharedLibsFlags();

  /** @return arguments to pass to the linker so that it ignores undefined symbols when linking. */
  Iterable<String> getIgnoreUndefinedSymbolsFlags();

  /**
   * Generate a necessary linker arguments to propagate undefined symbols to a link command. May
   * need to create a {@link BuildRule}, in which case, {@code target} will be used as its name.
   *
   * @param target the name to give any {@link BuildRule} that needs to be created to facilitate
   *     generating the arguments,
   * @param symbolFiles the symbols files, each listing undefined symbols, one per line, to add to
   *     the link.
   * @return the list of linker arguments needed to propagate the list of undefined symbols to the
   *     link command.
   */
  ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles);

  Iterable<Arg> getSharedLibFlag();

  Iterable<String> outputArgs(String path);

  boolean hasFilePathSizeLimitations();

  SharedLibraryLoadingType getSharedLibraryLoadingType();

  Optional<ExtraOutputsDeriver> getExtraOutputsDeriver();

  /** Derives extra outputs from linker args */
  interface ExtraOutputsDeriver {
    ImmutableMap<String, Path> deriveExtraOutputsFromArgs(
        ImmutableList<String> linkerArgs, Path output);
  }

  /** The various ways to link an output file. */
  enum LinkType {

    // Link as standalone executable.
    EXECUTABLE,

    // Link as shared library, which can be loaded into a process image.
    SHARED,

    // Mach-O only: Link as a bundle, which can be loaded into a process image and
    // use that image's symbols.
    MACH_O_BUNDLE,
  }

  /** The various ways to link in dependencies. */
  enum LinkableDepType {

    // Provide input suitable for statically linking this linkable (e.g. return references to
    // static libraries, libfoo.a).
    STATIC,

    // Provide input suitable for statically linking this linkable using PIC-enabled binaries
    // (e.g. return references to static libraries, libfoo_pic.a).
    STATIC_PIC,

    // Provide input suitable for dynamically linking this linkable (e.g. return references to
    // shared libraries, libfoo.so).
    SHARED
  }

  /** The various ways to load shared libraries on different platforms */
  enum SharedLibraryLoadingType {
    // Shared libraries are loaded via -rpath (*nix)
    RPATH,
    // Shared libraries are loaded from the same directory where a binary is located (windows)
    THE_SAME_DIRECTORY,
  }

  /**
   * The various styles of runtime library to which we can link shared objects. In some cases, it's
   * useful to link against a static version of the usual dynamic support library.
   */
  enum CxxRuntimeType {
    // Link in the C++ runtime library dynamically
    DYNAMIC,

    // Link in the C++ runtime statically
    STATIC,
  }
}
