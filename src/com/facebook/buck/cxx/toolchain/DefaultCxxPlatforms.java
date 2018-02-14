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

import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Utility class to create a C/C++ platform described in the "cxx" section of .buckconfig, with
 * reasonable system defaults.
 */
public class DefaultCxxPlatforms {

  // Utility class, do not instantiate.
  private DefaultCxxPlatforms() {}

  public static final Flavor FLAVOR = InternalFlavor.of("default");

  private static final Path DEFAULT_C_FRONTEND = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CXX_FRONTEND = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_AR = Paths.get("/usr/bin/ar");
  private static final Path DEFAULT_STRIP = Paths.get("/usr/bin/strip");
  private static final Path DEFAULT_RANLIB = Paths.get("/usr/bin/ranlib");
  private static final Path DEFAULT_NM = Paths.get("/usr/bin/nm");

  private static final Path DEFAULT_OSX_C_FRONTEND = Paths.get("/usr/bin/clang");
  private static final Path DEFAULT_OSX_CXX_FRONTEND = Paths.get("/usr/bin/clang++");

  private static final String DEFAULT_WINDOWS_CXX_FRONTEND = "cl";
  private static final String DEFAULT_WINDOWS_LINK = "link";
  private static final String DEFAULT_WINDOWS_LIB = "lib";
  private static final String DEFAULT_UNIX_RANLIB = "ranlib";

  public static CxxPlatform build(Platform platform, CxxBuckConfig config) {
    String sharedLibraryExtension;
    String sharedLibraryVersionedExtensionFormat;
    String staticLibraryExtension;
    String objectFileExtension;
    Path defaultCFrontend;
    Path defaultCxxFrontend;
    Path defaultLinker;
    LinkerProvider.Type linkerType;
    Archiver archiver;
    DebugPathSanitizer compilerSanitizer;
    Optional<String> binaryExtension;
    ImmutableMap<String, String> env = config.getEnvironment();
    Optional<CxxToolProvider.Type> defaultToolType = Optional.empty();
    Optional<ToolProvider> ranlib;
    PicType picTypeForSharedLinking;
    switch (platform) {
      case LINUX:
        sharedLibraryExtension = "so";
        sharedLibraryVersionedExtensionFormat = "so.%s";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = getExecutablePath("gcc", DEFAULT_C_FRONTEND, env);
        defaultCxxFrontend = getExecutablePath("g++", DEFAULT_CXX_FRONTEND, env);
        defaultLinker = defaultCxxFrontend;
        linkerType = LinkerProvider.Type.GNU;
        archiver = new GnuArchiver(getHashedFileTool(config, "ar", DEFAULT_AR, env));
        compilerSanitizer = new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());
        binaryExtension = Optional.empty();
        ranlib =
            Optional.of(
                new ConstantToolProvider(
                    getHashedFileTool(config, DEFAULT_UNIX_RANLIB, DEFAULT_RANLIB, env)));
        picTypeForSharedLinking = PicType.PIC;
        break;
      case MACOS:
        sharedLibraryExtension = "dylib";
        sharedLibraryVersionedExtensionFormat = ".%s.dylib";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = getExecutablePath("clang", DEFAULT_OSX_C_FRONTEND, env);
        defaultCxxFrontend = getExecutablePath("clang++", DEFAULT_OSX_CXX_FRONTEND, env);
        defaultLinker = defaultCxxFrontend;
        linkerType = LinkerProvider.Type.DARWIN;
        archiver = new BsdArchiver(getHashedFileTool(config, "ar", DEFAULT_AR, env));
        compilerSanitizer = new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());
        binaryExtension = Optional.empty();
        ranlib =
            Optional.of(
                new ConstantToolProvider(
                    getHashedFileTool(config, DEFAULT_UNIX_RANLIB, DEFAULT_RANLIB, env)));
        picTypeForSharedLinking = PicType.PIC;
        break;
      case WINDOWS:
        sharedLibraryExtension = "dll";
        sharedLibraryVersionedExtensionFormat = "dll";
        staticLibraryExtension = "lib";
        objectFileExtension = "obj";
        defaultCFrontend =
            getExecutablePath(
                DEFAULT_WINDOWS_CXX_FRONTEND, Paths.get(DEFAULT_WINDOWS_CXX_FRONTEND), env);
        defaultCxxFrontend =
            getExecutablePath(
                DEFAULT_WINDOWS_CXX_FRONTEND, Paths.get(DEFAULT_WINDOWS_CXX_FRONTEND), env);
        defaultLinker =
            getExecutablePath(DEFAULT_WINDOWS_LINK, Paths.get(DEFAULT_WINDOWS_LINK), env);
        linkerType = LinkerProvider.Type.WINDOWS;
        archiver =
            new WindowsArchiver(
                getHashedFileTool(
                    config, DEFAULT_WINDOWS_LIB, Paths.get(DEFAULT_WINDOWS_LIB), env));
        compilerSanitizer = new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());
        binaryExtension = Optional.of("exe");
        defaultToolType = Optional.of(CxxToolProvider.Type.WINDOWS);
        ranlib = Optional.empty();
        picTypeForSharedLinking = PicType.PDC;
        break;
      case FREEBSD:
        sharedLibraryExtension = "so";
        sharedLibraryVersionedExtensionFormat = "so.%s";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = getExecutablePath("gcc", DEFAULT_C_FRONTEND, env);
        defaultCxxFrontend = getExecutablePath("g++", DEFAULT_CXX_FRONTEND, env);
        defaultLinker = defaultCxxFrontend;
        linkerType = LinkerProvider.Type.GNU;
        archiver = new BsdArchiver(getHashedFileTool(config, "ar", DEFAULT_AR, env));
        compilerSanitizer = new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());
        binaryExtension = Optional.empty();
        ranlib =
            Optional.of(
                new ConstantToolProvider(
                    getHashedFileTool(config, DEFAULT_UNIX_RANLIB, DEFAULT_RANLIB, env)));
        picTypeForSharedLinking = PicType.PIC;
        break;
        // $CASES-OMITTED$
      default:
        throw new RuntimeException(String.format("Unsupported platform: %s", platform));
    }

    // These are wrapped behind suppliers because config.getSourcePath() verifies that the path
    // exists and we only want to do that verification if the tool is actually needed.
    Supplier<PathSourcePath> defaultCFrontendSupplier =
        MoreSuppliers.memoize(() -> config.getSourcePath(defaultCFrontend));
    Supplier<PathSourcePath> defaultCxxFrontendSupplier =
        MoreSuppliers.memoize(() -> config.getSourcePath(defaultCxxFrontend));
    PreprocessorProvider aspp = new PreprocessorProvider(defaultCFrontendSupplier, defaultToolType);
    CompilerProvider as = new CompilerProvider(defaultCFrontendSupplier, defaultToolType);

    PreprocessorProvider cpp = new PreprocessorProvider(defaultCFrontendSupplier, defaultToolType);
    CompilerProvider cc = new CompilerProvider(defaultCFrontendSupplier, defaultToolType);

    PreprocessorProvider cxxpp =
        new PreprocessorProvider(defaultCxxFrontendSupplier, defaultToolType);
    CompilerProvider cxx = new CompilerProvider(defaultCxxFrontendSupplier, defaultToolType);

    return CxxPlatforms.build(
        FLAVOR,
        platform,
        config,
        as,
        aspp,
        cc,
        cxx,
        cpp,
        cxxpp,
        new DefaultLinkerProvider(
            linkerType,
            new ConstantToolProvider(
                new HashedFileTool(() -> config.getSourcePath(defaultLinker)))),
        ImmutableList.of(),
        getHashedFileTool(config, "strip", DEFAULT_STRIP, env),
        ArchiverProvider.from(archiver),
        ranlib,
        new PosixNmSymbolNameTool(getHashedFileTool(config, "nm", DEFAULT_NM, env)),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        sharedLibraryExtension,
        sharedLibraryVersionedExtensionFormat,
        staticLibraryExtension,
        objectFileExtension,
        compilerSanitizer,
        new MungingDebugPathSanitizer(
            config.getDebugPathSanitizerLimit(),
            File.separatorChar,
            Paths.get("."),
            ImmutableBiMap.of()),
        ImmutableMap.of(),
        binaryExtension,
        config.getHeaderVerificationOrIgnore(),
        picTypeForSharedLinking);
  }

  private static Tool getHashedFileTool(
      CxxBuckConfig config,
      String executableName,
      Path unresolvedLocation,
      ImmutableMap<String, String> env) {
    return new HashedFileTool(
        () -> config.getSourcePath(getExecutablePath(executableName, unresolvedLocation, env)));
  }

  private static Path getExecutablePath(
      String executableName, Path unresolvedLocation, ImmutableMap<String, String> env) {
    return new ExecutableFinder()
        .getOptionalExecutable(Paths.get(executableName), env)
        .orElse(unresolvedLocation);
  }
}
