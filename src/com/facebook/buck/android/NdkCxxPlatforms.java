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

package com.facebook.buck.android;

import com.facebook.buck.cxx.CompilerProvider;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxToolProvider;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.ElfSharedLibraryInterfaceFactory;
import com.facebook.buck.cxx.GnuArchiver;
import com.facebook.buck.cxx.GnuLinker;
import com.facebook.buck.cxx.HeaderVerification;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.cxx.MungingDebugPathSanitizer;
import com.facebook.buck.cxx.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.PreprocessorProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.util.environment.Platform;
import com.facebook.infer.annotation.Assertions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class NdkCxxPlatforms {

  private static final Logger LOG = Logger.get(NdkCxxPlatforms.class);

  /**
   * Magic path prefix we use to denote the machine-specific location of the Android NDK. Why "@"?
   * It's uncommon enough to mark that path element as special while not being a metacharacter in
   * either make, shell, or regular expression syntax.
   *
   * <p>We also have prefixes for tool specific paths, even though they're sub-paths of
   * `@ANDROID_NDK_ROOT@`. This is to sanitize host-specific sub-directories in the toolchain (e.g.
   * darwin-x86_64) which would otherwise break determinism and caching when using
   * cross-compilation.
   */
  public static final String ANDROID_NDK_ROOT = "@ANDROID_NDK_ROOT@";

  /**
   * Magic string we substitute into debug paths in place of the build-host name, erasing the
   * difference between say, building on Darwin and building on Linux.
   */
  public static final String BUILD_HOST_SUBST = "@BUILD_HOST@";

  public static final NdkCxxPlatformCompiler.Type DEFAULT_COMPILER_TYPE =
      NdkCxxPlatformCompiler.Type.GCC;
  public static final String DEFAULT_TARGET_APP_PLATFORM = "android-9";
  public static final ImmutableSet<String> DEFAULT_CPU_ABIS =
      ImmutableSet.of("arm", "armv7", "x86");
  public static final NdkCxxRuntime DEFAULT_CXX_RUNTIME = NdkCxxRuntime.GNUSTL;

  private static final ImmutableMap<Platform, Host> BUILD_PLATFORMS =
      ImmutableMap.of(
          Platform.LINUX, Host.LINUX_X86_64,
          Platform.MACOS, Host.DARWIN_X86_64,
          Platform.WINDOWS, Host.WINDOWS_X86_64);

  // TODO(cjhopman): Does the preprocessor need the -std= flags? Right now we don't send them.
  /** Defaults for c and c++ flags */
  public static final ImmutableList<String> DEFAULT_COMMON_CFLAGS =
      ImmutableList.of(
          // Default to the C11 standard.
          "-std=gnu11");

  public static final ImmutableList<String> DEFAULT_COMMON_CXXFLAGS =
      ImmutableList.of(
          // Default to the C++11 standard.
          "-std=gnu++11", "-fno-exceptions", "-fno-rtti");

  public static final ImmutableList<String> DEFAULT_COMMON_CPPFLAGS =
      ImmutableList.of(
          // Disable searching for headers provided by the system.  This limits headers to just
          // those provided by the NDK and any library dependencies.
          "-nostdinc",
          // Default macro definitions applied to all builds.
          "-DNDEBUG",
          "-DANDROID");

  public static final ImmutableList<String> DEFAULT_COMMON_CXXPPFLAGS = DEFAULT_COMMON_CPPFLAGS;

  /** Flags used when compiling either C or C++ sources. */
  public static final ImmutableList<String> DEFAULT_COMMON_COMPILER_FLAGS =
      ImmutableList.of(
          // Default compiler flags provided by the NDK build makefiles.
          "-ffunction-sections", "-funwind-tables", "-fomit-frame-pointer", "-fno-strict-aliasing");

  /** Default linker flags added by the NDK. */
  public static final ImmutableList<String> DEFAULT_COMMON_LDFLAGS =
      ImmutableList.of(
          // Add a deterministic build ID to Android builds.
          // We use it to find symbols from arbitrary binaries.
          "-Wl,--build-id",
          // Enforce the NX (no execute) security feature
          "-Wl,-z,noexecstack",
          // Strip unused code
          "-Wl,--gc-sections",
          // Refuse to produce dynamic objects with undefined symbols
          "-Wl,-z,defs",
          // Forbid dangerous copy "relocations"
          "-Wl,-z,nocopyreloc",
          // We always pass the runtime library on the command line, so setting this flag
          // means the resulting link will only use it if it was actually needed it.
          "-Wl,--as-needed");

  // Utility class, do not instantiate.
  private NdkCxxPlatforms() {}

  static int getNdkMajorVersion(String ndkVersion) {
    return ndkVersion.startsWith("r9")
        ? 9
        : ndkVersion.startsWith("r10")
            ? 10
            : ndkVersion.startsWith("11.")
                ? 11
                : ndkVersion.startsWith("12.")
                    ? 12
                    : ndkVersion.startsWith("13.")
                        ? 13
                        : ndkVersion.startsWith("14.")
                            ? 14
                            : ndkVersion.startsWith("15.") ? 15 : -1;
  }

  public static String getDefaultGccVersionForNdk(Optional<String> ndkVersion) {
    if (ndkVersion.isPresent() && getNdkMajorVersion(ndkVersion.get()) < 11) {
      return "4.8";
    }
    return "4.9";
  }

  public static String getDefaultClangVersionForNdk(Optional<String> ndkVersion) {
    if (ndkVersion.isPresent() && getNdkMajorVersion(ndkVersion.get()) < 11) {
      return "3.5";
    }
    return "3.8";
  }

  public static boolean isSupportedConfiguration(Path ndkRoot, NdkCxxRuntime cxxRuntime) {
    // TODO(12846101): With ndk r12, Android has started to use libc++abi. Buck
    // needs to figure out how to support that.
    String ndkVersion = readVersion(ndkRoot);
    return !(cxxRuntime == NdkCxxRuntime.LIBCXX && getNdkMajorVersion(ndkVersion) >= 12);
  }

  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      NdkCxxRuntime cxxRuntime,
      String androidPlatform,
      Set<String> cpuAbis,
      Platform platform) {
    return getPlatforms(
        config,
        androidConfig,
        filesystem,
        ndkRoot,
        compiler,
        cxxRuntime,
        androidPlatform,
        cpuAbis,
        platform,
        new ExecutableFinder(),
        /* strictToolchainPaths */ true);
  }

  /** @return the map holding the available {@link NdkCxxPlatform}s. */
  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      NdkCxxRuntime cxxRuntime,
      String androidPlatform,
      Set<String> cpuAbis,
      Platform platform,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    ImmutableMap.Builder<TargetCpuType, NdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();

    // ARM Platform
    if (cpuAbis.contains("arm")) {
      NdkCxxPlatformTargetConfiguration targetConfiguration =
          getTargetConfiguration(TargetCpuType.ARM, compiler, androidPlatform);
      NdkCxxPlatform armeabi =
          build(
              config,
              androidConfig,
              filesystem,
              InternalFlavor.of("android-arm"),
              platform,
              ndkRoot,
              targetConfiguration,
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.ARM, armeabi);
    }

    // ARMv7 Platform
    if (cpuAbis.contains("armv7")) {
      NdkCxxPlatformTargetConfiguration targetConfiguration =
          getTargetConfiguration(TargetCpuType.ARMV7, compiler, androidPlatform);
      NdkCxxPlatform armeabiv7 =
          build(
              config,
              androidConfig,
              filesystem,
              InternalFlavor.of("android-armv7"),
              platform,
              ndkRoot,
              targetConfiguration,
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.ARMV7, armeabiv7);
    }

    // ARM64 Platform
    if (cpuAbis.contains("arm64")) {
      NdkCxxPlatformTargetConfiguration targetConfiguration =
          getTargetConfiguration(TargetCpuType.ARM64, compiler, androidPlatform);
      NdkCxxPlatform arm64 =
          build(
              config,
              androidConfig,
              filesystem,
              InternalFlavor.of("android-arm64"),
              platform,
              ndkRoot,
              targetConfiguration,
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.ARM64, arm64);
    }

    // x86 Platform
    if (cpuAbis.contains("x86")) {
      NdkCxxPlatformTargetConfiguration targetConfiguration =
          getTargetConfiguration(TargetCpuType.X86, compiler, androidPlatform);
      NdkCxxPlatform x86 =
          build(
              config,
              androidConfig,
              filesystem,
              InternalFlavor.of("android-x86"),
              platform,
              ndkRoot,
              targetConfiguration,
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.X86, x86);
    }

    // x86_64 Platform
    if (cpuAbis.contains("x86_64")) {
      NdkCxxPlatformTargetConfiguration targetConfiguration =
          getTargetConfiguration(TargetCpuType.X86_64, compiler, androidPlatform);
      // CHECKSTYLE.OFF: LocalVariableName

      NdkCxxPlatform x86_64 =
          // CHECKSTYLE.ON
          build(
              config,
              androidConfig,
              filesystem,
              InternalFlavor.of("android-x86_64"),
              platform,
              ndkRoot,
              targetConfiguration,
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.X86_64, x86_64);
    }

    return ndkCxxPlatformBuilder.build();
  }

  @VisibleForTesting
  static NdkCxxPlatformTargetConfiguration getTargetConfiguration(
      TargetCpuType targetCpuType, NdkCxxPlatformCompiler compiler, String androidPlatform) {
    switch (targetCpuType) {
      case ARM:
        ImmutableList<String> armeabiArchFlags =
            ImmutableList.of("-march=armv5te", "-mtune=xscale", "-msoft-float", "-mthumb");
        return NdkCxxPlatformTargetConfiguration.builder()
            .setToolchain(Toolchain.ARM_LINUX_ANDROIDEABI)
            .setTargetArch(TargetArch.ARM)
            .setTargetArchAbi(TargetArchAbi.ARMEABI)
            .setTargetAppPlatform(androidPlatform)
            .setCompiler(compiler)
            .setToolchainTarget(ToolchainTarget.ARM_LINUX_ANDROIDEABI)
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, armeabiArchFlags)
            .putAssemblerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "armv5te-none-linux-androideabi")
                    .addAll(armeabiArchFlags)
                    .build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.<String>builder().add("-Os").addAll(armeabiArchFlags).build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "armv5te-none-linux-androideabi", "-Os")
                    .addAll(armeabiArchFlags)
                    .build())
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.of("-march=armv5te", "-Wl,--fix-cortex-a8"))
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of(
                    "-target",
                    "armv5te-none-linux-androideabi",
                    "-march=armv5te",
                    "-Wl,--fix-cortex-a8"))
            .build();
      case ARMV7:
        ImmutableList<String> armeabiv7ArchFlags =
            ImmutableList.of("-march=armv7-a", "-mfpu=vfpv3-d16", "-mfloat-abi=softfp", "-mthumb");
        return NdkCxxPlatformTargetConfiguration.builder()
            .setToolchain(Toolchain.ARM_LINUX_ANDROIDEABI)
            .setTargetArch(TargetArch.ARM)
            .setTargetArchAbi(TargetArchAbi.ARMEABI_V7A)
            .setTargetAppPlatform(androidPlatform)
            .setCompiler(compiler)
            .setToolchainTarget(ToolchainTarget.ARM_LINUX_ANDROIDEABI)
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, armeabiv7ArchFlags)
            .putAssemblerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "armv7-none-linux-androideabi")
                    .addAll(armeabiv7ArchFlags)
                    .build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.<String>builder()
                    .add("-finline-limit=64", "-Os")
                    .addAll(armeabiv7ArchFlags)
                    .build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "armv7-none-linux-androideabi", "-Os")
                    .addAll(armeabiv7ArchFlags)
                    .build())
            .putLinkerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "armv7-none-linux-androideabi"))
            .build();
      case ARM64:
        ImmutableList<String> arm64ArchFlags = ImmutableList.of("-march=armv8-a");
        return NdkCxxPlatformTargetConfiguration.builder()
            .setToolchain(Toolchain.AARCH64_LINUX_ANDROID)
            .setTargetArch(TargetArch.ARM64)
            .setTargetArchAbi(TargetArchAbi.ARM64_V8A)
            .setTargetAppPlatform(androidPlatform)
            .setCompiler(compiler)
            .setToolchainTarget(ToolchainTarget.AARCH64_LINUX_ANDROID)
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, arm64ArchFlags)
            .putAssemblerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "aarch64-none-linux-android")
                    .addAll(arm64ArchFlags)
                    .build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.<String>builder()
                    .add("-O2")
                    .add("-fomit-frame-pointer")
                    .add("-fstrict-aliasing")
                    .add("-funswitch-loops")
                    .add("-finline-limit=300")
                    .addAll(arm64ArchFlags)
                    .build())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.<String>builder()
                    .add("-target", "aarch64-none-linux-android")
                    .add("-O2")
                    .add("-fomit-frame-pointer")
                    .add("-fstrict-aliasing")
                    .addAll(arm64ArchFlags)
                    .build())
            .putLinkerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "aarch64-none-linux-android"))
            .build();
      case X86:
        return NdkCxxPlatformTargetConfiguration.builder()
            .setToolchain(Toolchain.X86)
            .setTargetArch(TargetArch.X86)
            .setTargetArchAbi(TargetArchAbi.X86)
            .setTargetAppPlatform(androidPlatform)
            .setCompiler(compiler)
            .setToolchainTarget(ToolchainTarget.I686_LINUX_ANDROID)
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putAssemblerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "i686-none-linux-android"))
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.of("-funswitch-loops", "-finline-limit=300", "-O2"))
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "i686-none-linux-android", "-O2"))
            .putLinkerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "i686-none-linux-android"))
            .build();
      case X86_64:
        return NdkCxxPlatformTargetConfiguration.builder()
            .setToolchain(Toolchain.X86_64)
            .setTargetArch(TargetArch.X86_64)
            .setTargetArchAbi(TargetArchAbi.X86_64)
            .setTargetAppPlatform(androidPlatform)
            .setCompiler(compiler)
            .setToolchainTarget(ToolchainTarget.X86_64_LINUX_ANDROID)
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putAssemblerFlags(NdkCxxPlatformCompiler.Type.CLANG, ImmutableList.<String>of())
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.GCC,
                ImmutableList.of("-funswitch-loops", "-finline-limit=300", "-O2"))
            .putCompilerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "x86_64-none-linux-android", "-O2"))
            .putLinkerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.<String>of())
            .putLinkerFlags(
                NdkCxxPlatformCompiler.Type.CLANG,
                ImmutableList.of("-target", "x86_64-none-linux-android"))
            .build();
      case MIPS:
        break;
    }
    throw new AssertionError();
  }

  @VisibleForTesting
  static NdkCxxPlatform build(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Flavor flavor,
      Platform platform,
      Path ndkRoot,
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxRuntime cxxRuntime,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    // Create a version string to use when generating rule keys via the NDK tools we'll generate
    // below.  This will be used in lieu of hashing the contents of the tools, so that builds from
    // different host platforms (which produce identical output) will share the cache with one
    // another.
    NdkCxxPlatformCompiler.Type compilerType = targetConfiguration.getCompiler().getType();
    String version =
        Joiner.on('-')
            .join(
                ImmutableList.of(
                    readVersion(ndkRoot),
                    targetConfiguration.getToolchain(),
                    targetConfiguration.getTargetAppPlatform(),
                    compilerType,
                    targetConfiguration.getCompiler().getVersion(),
                    targetConfiguration.getCompiler().getGccVersion(),
                    cxxRuntime));

    Host host = Preconditions.checkNotNull(BUILD_PLATFORMS.get(platform));

    NdkCxxToolchainPaths toolchainPaths =
        new NdkCxxToolchainPaths(
            filesystem,
            ndkRoot,
            targetConfiguration,
            host.toString(),
            cxxRuntime,
            strictToolchainPaths);
    // Sanitized paths will have magic placeholders for parts of the paths that
    // are machine/host-specific. See comments on ANDROID_NDK_ROOT and
    // BUILD_HOST_SUBST above.
    NdkCxxToolchainPaths sanitizedPaths = toolchainPaths.getSanitizedPaths();

    // Build up the map of paths that must be sanitized.
    ImmutableBiMap.Builder<Path, Path> sanitizePathsBuilder = ImmutableBiMap.builder();
    sanitizePathsBuilder.put(toolchainPaths.getNdkToolRoot(), sanitizedPaths.getNdkToolRoot());
    if (compilerType != NdkCxxPlatformCompiler.Type.GCC) {
      sanitizePathsBuilder.put(
          toolchainPaths.getNdkGccToolRoot(), sanitizedPaths.getNdkGccToolRoot());
    }
    sanitizePathsBuilder.put(ndkRoot, Paths.get(ANDROID_NDK_ROOT));

    CxxToolProvider.Type type =
        compilerType == NdkCxxPlatformCompiler.Type.CLANG
            ? CxxToolProvider.Type.CLANG
            : CxxToolProvider.Type.GCC;
    ToolProvider ccTool =
        new ConstantToolProvider(
            getCTool(toolchainPaths, compilerType.getCc(), version, executableFinder));
    ToolProvider cxxTool =
        new ConstantToolProvider(
            getCTool(toolchainPaths, compilerType.getCxx(), version, executableFinder));
    CompilerProvider cc = new CompilerProvider(ccTool, type);
    PreprocessorProvider cpp = new PreprocessorProvider(ccTool, type);
    CompilerProvider cxx = new CompilerProvider(cxxTool, type);
    PreprocessorProvider cxxpp = new PreprocessorProvider(cxxTool, type);

    CxxPlatform.Builder cxxPlatformBuilder = CxxPlatform.builder();
    ImmutableBiMap<Path, Path> sanitizePaths = sanitizePathsBuilder.build();
    PrefixMapDebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(
            config.getDebugPathSanitizerLimit(),
            File.separatorChar,
            Paths.get("."),
            sanitizePaths,
            filesystem.getRootPath().toAbsolutePath(),
            type,
            filesystem);
    MungingDebugPathSanitizer assemblerDebugPathSanitizer =
        new MungingDebugPathSanitizer(
            config.getDebugPathSanitizerLimit(), File.separatorChar, Paths.get("."), sanitizePaths);
    cxxPlatformBuilder
        .setFlavor(flavor)
        .setAs(cc)
        .addAllAsflags(getAsflags(targetConfiguration, toolchainPaths))
        .setAspp(cpp)
        .setCc(cc)
        .addAllCflags(getCCompilationFlags(targetConfiguration, toolchainPaths, androidConfig))
        .setCpp(cpp)
        .addAllCppflags(getCPreprocessorFlags(targetConfiguration, toolchainPaths, androidConfig))
        .setCxx(cxx)
        .addAllCxxflags(getCxxCompilationFlags(targetConfiguration, toolchainPaths, androidConfig))
        .setCxxpp(cxxpp)
        .addAllCxxppflags(
            getCxxPreprocessorFlags(targetConfiguration, toolchainPaths, androidConfig))
        .setLd(
            new DefaultLinkerProvider(
                LinkerProvider.Type.GNU,
                new ConstantToolProvider(
                    getCcLinkTool(
                        targetConfiguration,
                        toolchainPaths,
                        compilerType.getCxx(),
                        version,
                        cxxRuntime,
                        executableFinder))))
        .addAllLdflags(getLdFlags(targetConfiguration, androidConfig))
        .setStrip(getGccTool(toolchainPaths, "strip", version, executableFinder))
        .setSymbolNameTool(
            new PosixNmSymbolNameTool(getGccTool(toolchainPaths, "nm", version, executableFinder)))
        .setAr(new GnuArchiver(getGccTool(toolchainPaths, "ar", version, executableFinder)))
        .setRanlib(getGccTool(toolchainPaths, "ranlib", version, executableFinder))
        // NDK builds are cross compiled, so the header is the same regardless of the host platform.
        .setCompilerDebugPathSanitizer(compilerDebugPathSanitizer)
        .setAssemblerDebugPathSanitizer(assemblerDebugPathSanitizer)
        .setSharedLibraryExtension("so")
        .setSharedLibraryVersionedExtensionFormat("so.%s")
        .setStaticLibraryExtension("a")
        .setObjectFileExtension("o")
        .setSharedLibraryInterfaceFactory(
            config.shouldUseSharedLibraryInterfaces()
                ? Optional.of(
                    ElfSharedLibraryInterfaceFactory.of(
                        new ConstantToolProvider(
                            getGccTool(toolchainPaths, "objcopy", version, executableFinder))))
                : Optional.empty())
        .setPublicHeadersSymlinksEnabled(config.getPublicHeadersSymlinksEnabled())
        .setPrivateHeadersSymlinksEnabled(config.getPrivateHeadersSymlinksEnabled());

    // Add the NDK root path to the white-list so that headers from the NDK won't trigger the
    // verification warnings.  Ideally, long-term, we'd model NDK libs/headers via automatically
    // generated nodes/descriptions so that they wouldn't need to special case it here.
    HeaderVerification headerVerification = config.getHeaderVerification();
    try {
      headerVerification =
          headerVerification.withPlatformWhitelist(
              ImmutableList.of(
                  "^"
                      + Pattern.quote(ndkRoot.toRealPath().toString() + File.separatorChar)
                      + ".*"));
    } catch (IOException e) {
      LOG.warn(e, "NDK path could not be resolved: %s", ndkRoot);
    }
    cxxPlatformBuilder.setHeaderVerification(headerVerification);
    LOG.debug("NDK root: %s", ndkRoot.toString());
    LOG.debug(
        "Headers verification platform whitelist: %s", headerVerification.getPlatformWhitelist());

    if (cxxRuntime != NdkCxxRuntime.SYSTEM) {
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.SHARED, "-l" + cxxRuntime.getSharedName());
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.STATIC, "-l" + cxxRuntime.getStaticName());
    }

    CxxPlatform cxxPlatform = cxxPlatformBuilder.build();

    NdkCxxPlatform.Builder builder = NdkCxxPlatform.builder();
    builder
        .setCxxPlatform(cxxPlatform)
        .setCxxRuntime(cxxRuntime)
        .setObjdump(getGccTool(toolchainPaths, "objdump", version, executableFinder));
    if (cxxRuntime != NdkCxxRuntime.SYSTEM) {
      builder.setCxxSharedRuntimePath(
          toolchainPaths.getCxxRuntimeLibsDirectory().resolve(cxxRuntime.getSoname()));
    }
    return builder.build();
  }

  /**
   * It returns the version of the Android NDK located at the {@code ndkRoot} or throws the
   * exception.
   *
   * @param ndkRoot the path where Android NDK is located.
   * @return the version of the Android NDK located in {@code ndkRoot}.
   */
  private static String readVersion(Path ndkRoot) {
    return DefaultAndroidDirectoryResolver.findNdkVersionFromDirectory(ndkRoot).get();
  }

  private static Path getToolPath(
      NdkCxxToolchainPaths toolchainPaths, String tool, ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getToolPath(tool);
    Optional<Path> path = executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Path getGccToolPath(
      NdkCxxToolchainPaths toolchainPaths, String tool, ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getGccToolchainBinPath().resolve(tool);
    Optional<Path> path = executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Tool getGccTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(getGccToolPath(toolchainPaths, tool, executableFinder), tool, version);
  }

  private static Tool getCTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(getToolPath(toolchainPaths, tool, executableFinder), tool, version);
  }

  private static ImmutableList<String> getCxxRuntimeIncludeFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    switch (toolchainPaths.getCxxRuntime()) {
      case GNUSTL:
        flags.add(
            "-isystem", toolchainPaths.getCxxRuntimeDirectory().resolve("include").toString());
        flags.add(
            "-isystem",
            toolchainPaths
                .getCxxRuntimeDirectory()
                .resolve("libs")
                .resolve(targetConfiguration.getTargetArchAbi().toString())
                .resolve("include")
                .toString());
        break;
      case LIBCXX:
        flags.add(
            "-isystem",
            toolchainPaths
                .getCxxRuntimeDirectory()
                .resolve("libcxx")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            toolchainPaths
                .getCxxRuntimeDirectory()
                .getParent()
                .resolve("llvm-libc++abi")
                .resolve("libcxxabi")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            toolchainPaths
                .getNdkRoot()
                .resolve("sources")
                .resolve("android")
                .resolve("support")
                .resolve("include")
                .toString());
        break;
        // $CASES-OMITTED$
      default:
        flags.add(
            "-isystem", toolchainPaths.getCxxRuntimeDirectory().resolve("include").toString());
    }
    return flags.build();
  }

  private static Linker getCcLinkTool(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      NdkCxxRuntime cxxRuntime,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add("-gcc-toolchain", toolchainPaths.getNdkGccToolRoot().toString());
    }

    // Set the sysroot to the platform-specific path.
    flags.add("--sysroot=" + toolchainPaths.getSysroot());

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add("-B" + toolchainPaths.getLibexecGccToolPath(), "-B" + toolchainPaths.getLibPath());
    }

    // Add the path to the C/C++ runtime libraries, if necessary.
    if (cxxRuntime != NdkCxxRuntime.SYSTEM) {
      flags.add("-L" + toolchainPaths.getCxxRuntimeLibsDirectory().toString());
    }

    return new GnuLinker(
        VersionedTool.builder()
            .setPath(getToolPath(toolchainPaths, tool, executableFinder))
            .setName(tool)
            .setVersion(version)
            .setExtraArgs(flags.build())
            .build());
  }

  private static ImmutableList<String> getLdFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getLinkerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_LDFLAGS)
        .addAll(config.getExtraNdkLdFlags())
        .build();
  }

  /** Flags to be used when either preprocessing or compiling C or C++ sources. */
  private static ImmutableList<String> getCommonFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find the GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add("-gcc-toolchain", toolchainPaths.getNdkGccToolRoot().toString());
    }

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add(
          "-B" + toolchainPaths.getLibexecGccToolPath(),
          "-B" + toolchainPaths.getToolchainBinPath());
    }

    // Enable default warnings and turn them into errors.
    flags.add("-Wall", "-Werror");

    // NOTE:  We pass all compiler flags to the preprocessor to make sure any necessary internal
    // macros get defined and we also pass the include paths to the to the compiler since we're
    // not whether we're doing combined preprocessing/compiling or not.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add("-Wno-unused-command-line-argument");
    }

    // NDK builds enable stack protector and debug symbols by default.
    flags.add("-fstack-protector", "-g3");

    return flags.build();
  }

  private static ImmutableList<String> getCommonIncludes(NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.of(
        "-isystem",
        toolchainPaths.getNdkToolRoot().resolve("include").toString(),
        "-isystem",
        toolchainPaths.getLibPath().resolve("include").toString(),
        "-isystem",
        toolchainPaths.getSysroot().resolve("usr").resolve("include").toString(),
        "-isystem",
        toolchainPaths.getSysroot().resolve("usr").resolve("include").resolve("linux").toString());
  }

  private static ImmutableList<String> getAsflags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        // Default assembler flags added by the NDK to enforce the NX (no execute) security feature.
        .add("-Xassembler", "--noexecstack")
        .addAll(targetConfiguration.getAssemblerFlags(targetConfiguration.getCompiler().getType()))
        .build();
  }

  // TODO(cjhopman): The way that c/cpp/cxx/cxxpp flags work is rather unintuitive. The
  // documentation states that cflags/cxxflags are added to both preprocess and compile,
  // cppflags/cxxppflags are added only to the preprocessor flags. At runtime, we typically do
  // preprocess+compile, and in that case we're going to add both the preprocess and the compile
  // flags to the command line. Still, BUCK expects that a CxxPlatform can do all of
  // preprocess/compile/preprocess+compile. Many of the flags are duplicated across both preprocess
  // and compile to support that (and then typically our users have to deal with ridiculously long
  // command lines because we only ever do preprocess+compile).
  private static ImmutableList<String> getCPreprocessorFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(getCommonIncludes(toolchainPaths))
        .addAll(DEFAULT_COMMON_CPPFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_CFLAGS)
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(config.getExtraNdkCFlags())
        .build();
  }

  private static ImmutableList<String> getCxxPreprocessorFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(getCxxRuntimeIncludeFlags(targetConfiguration, toolchainPaths));
    flags.addAll(getCommonIncludes(toolchainPaths));
    flags.addAll(DEFAULT_COMMON_CXXPPFLAGS);
    flags.addAll(getCommonFlags(targetConfiguration, toolchainPaths));
    flags.addAll(DEFAULT_COMMON_CXXFLAGS);
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add("-Wno-literal-suffix");
    }
    flags.addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()));
    flags.addAll(config.getExtraNdkCxxFlags());
    return flags.build();
  }

  private static ImmutableList<String> getCCompilationFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_CFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_COMPILER_FLAGS)
        .addAll(config.getExtraNdkCFlags())
        .build();
  }

  private static ImmutableList<String> getCxxCompilationFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_CXXFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_COMPILER_FLAGS)
        .addAll(config.getExtraNdkCxxFlags())
        .build();
  }

  /** The CPU architectures to target. */
  public enum TargetCpuType {
    ARM,
    ARMV7,
    ARM64,
    X86,
    X86_64,
    MIPS,
  }

  /** The build toolchain, named (including compiler version) after the target platform/arch. */
  public enum Toolchain {
    X86("x86"),
    X86_64("x86_64"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
    AARCH64_LINUX_ANDROID("aarch64-linux-android"),
    ;

    private final String value;

    Toolchain(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** Name of the target CPU architecture. */
  public enum TargetArch {
    X86("x86"),
    X86_64("x86_64"),
    ARM("arm"),
    ARM64("arm64"),
    ;

    private final String value;

    TargetArch(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** Name of the target CPU + ABI. */
  public enum TargetArchAbi {
    X86("x86"),
    X86_64("x86_64"),
    ARMEABI("armeabi"),
    ARMEABI_V7A("armeabi-v7a"),
    ARM64_V8A("arm64-v8a"),
    ;

    private final String value;

    TargetArchAbi(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** The OS and Architecture that we're building on. */
  public enum Host {
    DARWIN_X86_64("darwin-x86_64"),
    LINUX_X86_64("linux-x86_64"),
    WINDOWS_X86_64("windows-x86_64"),
    ;

    private final String value;

    Host(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** The toolchains name for the platform being targeted. */
  public enum ToolchainTarget {
    I686_LINUX_ANDROID("i686-linux-android"),
    X86_64_LINUX_ANDROID("x86_64-linux-android"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
    AARCH64_LINUX_ANDROID("aarch64-linux-android"),
    ;

    private final String value;

    ToolchainTarget(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  static class NdkCxxToolchainPaths {
    private Path ndkRoot;
    private String ndkVersion;
    private NdkCxxPlatformTargetConfiguration targetConfiguration;
    private String hostName;
    private NdkCxxRuntime cxxRuntime;
    private Map<String, Path> cachedPaths;
    private boolean strict;
    private int ndkMajorVersion;
    private ProjectFilesystem filesystem;

    NdkCxxToolchainPaths(
        ProjectFilesystem filesystem,
        Path ndkRoot,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        NdkCxxRuntime cxxRuntime,
        boolean strict) {
      this(
          filesystem,
          ndkRoot,
          readVersion(ndkRoot),
          targetConfiguration,
          hostName,
          cxxRuntime,
          strict);
    }

    private NdkCxxToolchainPaths(
        ProjectFilesystem filesystem,
        Path ndkRoot,
        String ndkVersion,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        NdkCxxRuntime cxxRuntime,
        boolean strict) {
      this.filesystem = filesystem;
      this.cachedPaths = new HashMap<>();
      this.strict = strict;

      this.targetConfiguration = targetConfiguration;
      this.hostName = hostName;
      this.cxxRuntime = cxxRuntime;
      this.ndkRoot = ndkRoot;
      this.ndkVersion = ndkVersion;
      this.ndkMajorVersion = getNdkMajorVersion(ndkVersion);

      Assertions.assertCondition(ndkMajorVersion > 0, "Unknown ndk version: " + ndkVersion);
    }

    NdkCxxToolchainPaths getSanitizedPaths() {
      return new NdkCxxToolchainPaths(
          filesystem,
          Paths.get(ANDROID_NDK_ROOT),
          ndkVersion,
          targetConfiguration,
          BUILD_HOST_SUBST,
          cxxRuntime,
          false);
    }

    Path processPathPattern(Path root, String pattern) {
      String key = root.toString() + "/" + pattern;
      Path result = cachedPaths.get(key);
      if (result == null) {
        String[] segments = pattern.split("/");
        result = root;
        for (String s : segments) {
          if (s.contains("{")) {
            s = s.replace("{toolchain}", targetConfiguration.getToolchain().toString());
            s =
                s.replace(
                    "{toolchain_target}", targetConfiguration.getToolchainTarget().toString());
            s = s.replace("{compiler_version}", targetConfiguration.getCompiler().getVersion());
            s = s.replace("{compiler_type}", targetConfiguration.getCompiler().getType().getName());
            s =
                s.replace(
                    "{gcc_compiler_version}", targetConfiguration.getCompiler().getGccVersion());
            s = s.replace("{hostname}", hostName);
            s = s.replace("{target_platform}", targetConfiguration.getTargetAppPlatform());
            s = s.replace("{target_arch}", targetConfiguration.getTargetArch().toString());
            s = s.replace("{target_arch_abi}", targetConfiguration.getTargetArchAbi().toString());
          }
          result = result.resolve(s);
        }
        if (strict) {
          Assertions.assertCondition(
              result.toFile().exists(), result.toString() + " doesn't exist.");
        }
        cachedPaths.put(key, result);
      }
      return result;
    }

    private boolean isGcc() {
      return targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC;
    }

    Path processPathPattern(String s) {
      return processPathPattern(ndkRoot, s);
    }

    Path getNdkToolRoot() {
      if (isGcc()) {
        return processPathPattern("toolchains/{toolchain}-{compiler_version}/prebuilt/{hostname}");
      } else {
        if (ndkMajorVersion < 11) {
          return processPathPattern("toolchains/llvm-{compiler_version}/prebuilt/{hostname}");
        } else {
          return processPathPattern("toolchains/llvm/prebuilt/{hostname}");
        }
      }
    }

    /**
     * @return the path to use as the system root, targeted to the given target platform and
     *     architecture.
     */
    Path getSysroot() {
      return processPathPattern("platforms/{target_platform}/arch-{target_arch}");
    }

    Path getLibexecGccToolPath() {
      Assertions.assertCondition(isGcc());
      if (ndkMajorVersion < 12) {
        return processPathPattern(
            getNdkToolRoot(), "libexec/gcc/{toolchain_target}/{compiler_version}");
      } else {
        return processPathPattern(
            getNdkToolRoot(), "libexec/gcc/{toolchain_target}/{compiler_version}.x");
      }
    }

    Path getLibPath() {
      String pattern;
      if (isGcc()) {
        if (ndkMajorVersion < 12) {
          pattern = "lib/{compiler_type}/{toolchain_target}/{compiler_version}";
        } else {
          pattern = "lib/{compiler_type}/{toolchain_target}/{compiler_version}.x";
        }
      } else {
        if (ndkMajorVersion < 11) {
          pattern = "lib/{compiler_type}/{compiler_version}";
        } else {
          pattern = "lib64/{compiler_type}/{compiler_version}";
        }
      }
      return processPathPattern(getNdkToolRoot(), pattern);
    }

    Path getNdkGccToolRoot() {
      return processPathPattern(
          "toolchains/{toolchain}-{gcc_compiler_version}/prebuilt/{hostname}");
    }

    Path getToolchainBinPath() {
      if (isGcc()) {
        return processPathPattern(getNdkToolRoot(), "{toolchain_target}/bin");
      } else {
        return processPathPattern(getNdkToolRoot(), "bin");
      }
    }

    private Path getGccToolchainBinPath() {
      return processPathPattern(getNdkGccToolRoot(), "{toolchain_target}/bin");
    }

    private Path getCxxRuntimeDirectory() {
      if (cxxRuntime == NdkCxxRuntime.GNUSTL) {
        return processPathPattern(
            "sources/cxx-stl/" + cxxRuntime.getName() + "/{gcc_compiler_version}");
      } else {
        return processPathPattern("sources/cxx-stl/" + cxxRuntime.getName());
      }
    }

    private Path getCxxRuntimeLibsDirectory() {
      return processPathPattern(getCxxRuntimeDirectory(), "libs/{target_arch_abi}");
    }

    Path getToolPath(String tool) {
      if (isGcc()) {
        return processPathPattern(getNdkToolRoot(), "bin/{toolchain_target}-" + tool);
      } else {
        return processPathPattern(getNdkToolRoot(), "bin/" + tool);
      }
    }

    public Path getNdkRoot() {
      return ndkRoot;
    }

    public NdkCxxRuntime getCxxRuntime() {
      return cxxRuntime;
    }
  }
}
