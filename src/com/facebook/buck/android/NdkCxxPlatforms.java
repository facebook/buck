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
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.GnuArchiver;
import com.facebook.buck.cxx.GnuLinker;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.cxx.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.PreprocessorProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.util.environment.Platform;
import com.facebook.infer.annotation.Assertions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class NdkCxxPlatforms {

  /**
   * Magic path prefix we use to denote the machine-specific location of the Android NDK.  Why "@"?
   * It's uncommon enough to mark that path element as special while not being a metacharacter in
   * either make, shell, or regular expression syntax.
   *
   * We also have prefixes for tool specific paths, even though they're sub-paths of
   * `@ANDROID_NDK_ROOT@`.  This is to sanitize host-specific sub-directories in the toolchain
   * (e.g. darwin-x86_64) which would otherwise break determinism and caching when using
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
  public static final NdkCxxPlatforms.CxxRuntime DEFAULT_CXX_RUNTIME =
      NdkCxxPlatforms.CxxRuntime.GNUSTL;

  private static final ImmutableMap<Platform, Host> BUILD_PLATFORMS =
      ImmutableMap.of(
          Platform.LINUX, Host.LINUX_X86_64,
          Platform.MACOS, Host.DARWIN_X86_64,
          Platform.WINDOWS, Host.WINDOWS_X86_64);

  // Utility class, do not instantiate.
  private NdkCxxPlatforms() { }

  static int getNdkMajorVersion(String ndkVersion) {
    return
        ndkVersion.startsWith("r9") ? 9 :
        ndkVersion.startsWith("r10") ? 10 :
        ndkVersion.startsWith("11.") ? 11 :
        ndkVersion.startsWith("12.") ? 12 :
        ndkVersion.startsWith("13.") ? 13 :
        -1;
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

  public static boolean isSupportedConfiguration(
      Path ndkRoot,
      CxxRuntime cxxRuntime) {
    // TODO(12846101): With ndk r12, Android has started to use libc++abi. Buck
    // needs to figure out how to support that.
    String ndkVersion = readVersion(ndkRoot);
    return !(
      cxxRuntime == NdkCxxPlatforms.CxxRuntime.LIBCXX &&
      getNdkMajorVersion(ndkVersion) >= 12);
  }

  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      CxxRuntime cxxRuntime,
      String androidPlatform,
      Set<String> cpuAbis,
      Platform platform) {
    return getPlatforms(
        config,
        ndkRoot,
        compiler,
        cxxRuntime,
        androidPlatform,
        cpuAbis,
        platform,
        new ExecutableFinder(),
        /* strictToolchainPaths */ true);
  }

  /**
   * @return the map holding the available {@link NdkCxxPlatform}s.
   */
  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      CxxRuntime cxxRuntime,
      String androidPlatform,
      Set<String> cpuAbis,
      Platform platform,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    ImmutableMap.Builder<TargetCpuType, NdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();

    // ARM Platform
    if (cpuAbis.contains("arm")) {
      ImmutableList<String> armeabiArchFlags =
          ImmutableList.of(
              "-march=armv5te",
              "-mtune=xscale",
              "-msoft-float",
              "-mthumb");
      NdkCxxPlatform armeabi =
          build(
              config,
              ImmutableFlavor.of("android-arm"),
              platform,
              ndkRoot,
              NdkCxxPlatformTargetConfiguration.builder()
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
                      ImmutableList.<String>builder()
                          .add("-Os")
                          .addAll(armeabiArchFlags)
                          .build())
                  .putCompilerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.<String>builder()
                          .add("-target", "armv5te-none-linux-androideabi", "-Os")
                          .addAll(armeabiArchFlags)
                          .build())
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of(
                          "-march=armv5te",
                          "-Wl,--fix-cortex-a8"))
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of(
                          "-target", "armv5te-none-linux-androideabi",
                          "-march=armv5te",
                          "-Wl,--fix-cortex-a8"))
                  .build(),
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.ARM, armeabi);
    }

    // ARMv7 Platform
    if (cpuAbis.contains("armv7")) {
      ImmutableList<String> armeabiv7ArchFlags =
          ImmutableList.of(
              "-march=armv7-a",
              "-mfpu=vfpv3-d16",
              "-mfloat-abi=softfp",
              "-mthumb");
      NdkCxxPlatform armeabiv7 =
          build(
              config,
              ImmutableFlavor.of("android-armv7"),
              platform,
              ndkRoot,
              NdkCxxPlatformTargetConfiguration.builder()
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
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of())
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of("-target", "armv7-none-linux-androideabi"))
                  .build(),
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.ARMV7, armeabiv7);
    }

    // x86 Platform
    if (cpuAbis.contains("x86")) {
      NdkCxxPlatform x86 =
          build(
              config,
              ImmutableFlavor.of("android-x86"),
              platform,
              ndkRoot,
              NdkCxxPlatformTargetConfiguration.builder()
                  .setToolchain(Toolchain.X86)
                  .setTargetArch(TargetArch.X86)
                  .setTargetArchAbi(TargetArchAbi.X86)
                  .setTargetAppPlatform(androidPlatform)
                  .setCompiler(compiler)
                  .setToolchainTarget(ToolchainTarget.I686_LINUX_ANDROID)
                  .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.of())
                  .putAssemblerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.<String>builder()
                          .add("-target", "i686-none-linux-android")
                          .build())
                  .putCompilerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of(
                          "-funswitch-loops",
                          "-finline-limit=300",
                          "-O2"))
                  .putCompilerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of(
                          "-target", "i686-none-linux-android",
                          "-O2"))
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of())
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of(
                          "-target", "i686-none-linux-android"))
                  .build(),
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.X86, x86);
    }

    // x86_64 Platform
    if (cpuAbis.contains("x86_64")) {
      // CHECKSTYLE.OFF: LocalVariableName
      NdkCxxPlatform x86_64 =
      // CHECKSTYLE.ON
          build(
              config,
              ImmutableFlavor.of("android-x86_64"),
              platform,
              ndkRoot,
              NdkCxxPlatformTargetConfiguration.builder()
                  .setToolchain(Toolchain.X86_64)
                  .setTargetArch(TargetArch.X86_64)
                  .setTargetArchAbi(TargetArchAbi.X86_64)
                  .setTargetAppPlatform(androidPlatform)
                  .setCompiler(compiler)
                  .setToolchainTarget(ToolchainTarget.X86_64_LINUX_ANDROID)
                  .putAssemblerFlags(NdkCxxPlatformCompiler.Type.GCC, ImmutableList.of())
                  .putAssemblerFlags(NdkCxxPlatformCompiler.Type.CLANG, ImmutableList.of())
                  .putCompilerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of(
                          "-funswitch-loops",
                          "-finline-limit=300",
                          "-O2"))
                  .putCompilerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of(
                          "-target", "i686-none-linux-android",
                          "-O2"))
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.GCC,
                      ImmutableList.of())
                  .putLinkerFlags(
                      NdkCxxPlatformCompiler.Type.CLANG,
                      ImmutableList.of(
                          "-target", "i686-none-linux-android"))
                  .build(),
              cxxRuntime,
              executableFinder,
              strictToolchainPaths);
      ndkCxxPlatformBuilder.put(TargetCpuType.X86_64, x86_64);
    }

    return ndkCxxPlatformBuilder.build();
  }

  @VisibleForTesting
  static NdkCxxPlatform build(
      CxxBuckConfig config,
      Flavor flavor,
      Platform platform,
      Path ndkRoot,
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      CxxRuntime cxxRuntime,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    // Create a version string to use when generating rule keys via the NDK tools we'll generate
    // below.  This will be used in lieu of hashing the contents of the tools, so that builds from
    // different host platforms (which produce identical output) will share the cache with one
    // another.
    NdkCxxPlatformCompiler.Type compilerType = targetConfiguration.getCompiler().getType();
    String version =
        Joiner.on('-').join(
            ImmutableList.of(
                readVersion(ndkRoot),
                targetConfiguration.getToolchain(),
                targetConfiguration.getTargetAppPlatform(),
                compilerType,
                targetConfiguration.getCompiler().getVersion(),
                targetConfiguration.getCompiler().getGccVersion(),
                cxxRuntime));

    Host host = Preconditions.checkNotNull(BUILD_PLATFORMS.get(platform));

    NdkCxxToolchainPaths toolchainPaths = new NdkCxxToolchainPaths(
        ndkRoot, targetConfiguration, host.toString(), strictToolchainPaths);
    // Sanitized paths will have magic placeholders for parts of the paths that
    // are machine/host-specific. See comments on ANDROID_NDK_ROOT and
    // BUILD_HOST_SUBST above.
    NdkCxxToolchainPaths sanitizedPaths = toolchainPaths.getSanitizedPaths();

    // Build up the map of paths that must be sanitized.
    ImmutableBiMap.Builder<Path, Path> sanitizePaths = ImmutableBiMap.builder();
    sanitizePaths.put(toolchainPaths.getNdkToolRoot(), sanitizedPaths.getNdkToolRoot());
    if (compilerType != NdkCxxPlatformCompiler.Type.GCC) {
        sanitizePaths.put(toolchainPaths.getNdkGccToolRoot(), sanitizedPaths.getNdkGccToolRoot());
    }
    sanitizePaths.put(ndkRoot, Paths.get(ANDROID_NDK_ROOT));

    CxxToolProvider.Type type =
        compilerType == NdkCxxPlatformCompiler.Type.CLANG ?
            CxxToolProvider.Type.CLANG :
            CxxToolProvider.Type.GCC;
    ToolProvider ccTool =
        new ConstantToolProvider(
            getCTool(
                toolchainPaths,
                compilerType.getCc(),
                version,
                executableFinder));
    ToolProvider cxxTool =
        new ConstantToolProvider(
            getCTool(
                toolchainPaths,
                compilerType.getCxx(),
                version,
                executableFinder));
    CompilerProvider cc = new CompilerProvider(ccTool, type);
    PreprocessorProvider cpp = new PreprocessorProvider(ccTool, type);
    CompilerProvider cxx = new CompilerProvider(cxxTool, type);
    PreprocessorProvider cxxpp = new PreprocessorProvider(cxxTool, type);

    CxxPlatform.Builder cxxPlatformBuilder = CxxPlatform.builder();
    cxxPlatformBuilder
        .setFlavor(flavor)
        .setAs(cc)
        .addAllAsflags(getAsflags(targetConfiguration, toolchainPaths))
        .setAspp(cpp)
        .setCc(cc)
        .addAllCflags(getCflagsInternal(targetConfiguration, toolchainPaths))
        .setCpp(cpp)
        .addAllCppflags(getCppflags(targetConfiguration, toolchainPaths))
        .setCxx(cxx)
        .addAllCxxflags(getCxxflagsInternal(targetConfiguration, toolchainPaths))
        .setCxxpp(cxxpp)
        .addAllCxxppflags(getCxxppflags(targetConfiguration, toolchainPaths, cxxRuntime))
        .setLd(
            new DefaultLinkerProvider(
                LinkerProvider.Type.GNU,
                new ConstantToolProvider(
                    getCcLinkTool(
                        targetConfiguration,
                        toolchainPaths,
                        cxxRuntime,
                        compilerType.getCxx(),
                        version,
                        executableFinder))))
        .addAllLdflags(
            targetConfiguration.getLinkerFlags(compilerType))
        // Default linker flags added by the NDK
        .addLdflags(
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
            "-Wl,--as-needed")
        .setStrip(
            getGccTool(toolchainPaths, "strip", version, executableFinder))
        .setSymbolNameTool(
            new PosixNmSymbolNameTool(
                getGccTool(toolchainPaths, "nm", version, executableFinder)))
        .setAr(
            new GnuArchiver(
                getGccTool(toolchainPaths, "ar", version, executableFinder)))
        .setRanlib(
            getGccTool(toolchainPaths, "ranlib", version, executableFinder))
        // NDK builds are cross compiled, so the header is the same regardless of the host platform.
        .setDebugPathSanitizer(
            new DebugPathSanitizer(
                config.getDebugPathSanitizerLimit(),
                File.separatorChar,
                Paths.get("."),
                sanitizePaths.build()))
        .setSharedLibraryExtension("so")
        .setSharedLibraryVersionedExtensionFormat("so.%s")
        .setStaticLibraryExtension("a")
        .setObjectFileExtension("o")
    ;

    if (cxxRuntime != CxxRuntime.SYSTEM) {
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.SHARED, "-l" + cxxRuntime.getSharedName());
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.STATIC, "-l" + cxxRuntime.getStaticName());
    }

    CxxPlatform cxxPlatform = cxxPlatformBuilder.build();

    return NdkCxxPlatform.builder()
        .setCxxPlatform(cxxPlatform)
        .setCxxRuntime(cxxRuntime)
        .setCxxSharedRuntimePath(
            toolchainPaths.getCxxRuntimeLibsDirectory(cxxRuntime)
                .resolve(cxxRuntime.getSoname()))
        .setObjdump(
            getGccTool(toolchainPaths, "objdump", version, executableFinder))
        .build();
  }

  /**
   * It returns the version of the Android NDK located at the {@code ndkRoot} or throws the
   * exception.
   * @param ndkRoot the path where Android NDK is located.
   * @return the version of the Android NDK located in {@code ndkRoot}.
   */
  private static String readVersion(Path ndkRoot) {
    return DefaultAndroidDirectoryResolver.findNdkVersionFromDirectory(ndkRoot).get();
  }

  private static Path getToolPath(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getToolPath(tool);
    Optional<Path> path =
        executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Path getGccToolPath(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getGccToolchainBinPath().resolve(tool);
    Optional<Path> path =
        executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Tool getGccTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(
        getGccToolPath(toolchainPaths, tool, executableFinder),
        tool,
        version);
  }

  private static Tool getCTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(
        getToolPath(toolchainPaths, tool, executableFinder),
        tool,
        version);
  }

  private static ImmutableList<String> getCxxRuntimeIncludeFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      CxxRuntime cxxRuntime) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    switch (cxxRuntime) {
      case GNUSTL:
        flags.add(
            "-isystem",
            toolchainPaths.getCxxRuntimeDirectory(cxxRuntime)
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            toolchainPaths.getCxxRuntimeDirectory(cxxRuntime)
                .resolve("libs")
                .resolve(targetConfiguration.getTargetArchAbi().toString())
                .resolve("include")
                .toString());
        break;
      case LIBCXX:
        flags.add(
            "-isystem",
            toolchainPaths.getCxxRuntimeDirectory(cxxRuntime)
                .resolve("libcxx")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            toolchainPaths.getCxxRuntimeDirectory(cxxRuntime)
                .getParent()
                .resolve("llvm-libc++abi")
                .resolve("libcxxabi")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            toolchainPaths.getNdkRoot()
                .resolve("sources")
                .resolve("android")
                .resolve("support")
                .resolve("include")
                .toString());
        break;
      // $CASES-OMITTED$
      default:
        flags.add(
            "-isystem",
            toolchainPaths.getCxxRuntimeDirectory(cxxRuntime)
                .resolve("include")
                .toString());
    }
    return flags.build();
  }

  private static Linker getCcLinkTool(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      CxxRuntime cxxRuntime,
      String tool,
      String version,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add(
          "-gcc-toolchain",
          toolchainPaths.getNdkGccToolRoot().toString());
    }

    // Set the sysroot to the platform-specific path.
    flags.add("--sysroot=" + toolchainPaths.getSysroot());

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add(
          "-B" + toolchainPaths.getLibexecGccToolPath(),
          "-B" + toolchainPaths.getLibPath());
    }

    // Add the path to the C/C++ runtime libraries.
    flags.add(
        "-L" + toolchainPaths.getCxxRuntimeLibsDirectory(cxxRuntime).toString());

    return new GnuLinker(
        VersionedTool.builder()
            .setPath(getToolPath(toolchainPaths, tool, executableFinder))
            .setName(tool)
            .setVersion(version)
            .setExtraArgs(flags.build())
            .build());
  }

  /**
   * Flags to be used when either preprocessing or compiling C or C++ sources.
   */
  private static ImmutableList<String> getCommonFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find the GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add(
          "-gcc-toolchain",
          toolchainPaths.getNdkGccToolRoot().toString());
    }

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add(
          "-B" + toolchainPaths.getLibexecGccToolPath(),
          "-B" + toolchainPaths.getToolchainBinPath());
    }

    // Enable default warnings and turn them into errors.
    flags.add(
        "-Wall",
        "-Werror");

    // NOTE:  We pass all compiler flags to the preprocessor to make sure any necessary internal
    // macros get defined and we also pass the include paths to the to the compiler since we're
    // not whether we're doing combined preprocessing/compiling or not.
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.CLANG) {
      flags.add("-Wno-unused-command-line-argument");
    }

    // NDK builds enable stack protector and debug symbols by default.
    flags.add(
        "-fstack-protector",
        "-g3");

    return flags.build();
  }

  /**
   * Flags to be used when either preprocessing or compiling C sources.
   */
  private static ImmutableList<String> getCommonCFlags() {
    return ImmutableList.of(
        // Default to the newer C11 standard.  This is *not* a default set in the NDK.
        // Since this flag can be used multiple times, and because the compiler just uses
        // whichever standard was specified last, cxx_library rules can override this from
        // their BUCK-file definitions.
        "-std=gnu11");
  }

  /**
   * Flags to be used when either preprocessing or compiling C++ sources.
   */
  private static ImmutableList<String> getCommonCxxFlags() {
    return ImmutableList.of(
        // Default to the newer C++11 standard.  This is *not* a default set in the NDK.
        // Since this flag can be used multiple times, and because the compiler just uses
        // whichever standard was specified last, cxx_library rules can override this from
        // their BUCK-file definitions.
        "-std=gnu++11",
        // By default, Android builds disable exceptions and runtime type identification.
        "-fno-exceptions",
        "-fno-rtti");
  }

  /**
   * Flags to be used when preprocessing C or C++ sources.
   */
  private static ImmutableList<String> getCommonPreprocessorFlags() {
    return ImmutableList.of(
        // Disable searching for headers provided by the system.  This limits headers to just
        // those provided by the NDK and any library dependencies.
        "-nostdinc",
        // Default macro definitions applied to all builds.
        "-DNDEBUG",
        "-DANDROID");
  }

  private static ImmutableList<String> getCommonIncludes(
      NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.of(
        "-isystem",
        toolchainPaths.getNdkToolRoot()
            .resolve("include")
            .toString(),
        "-isystem",
        toolchainPaths.getLibPath()
            .resolve("include")
            .toString(),
        "-isystem",
        toolchainPaths.getSysroot()
            .resolve("usr")
            .resolve("include")
            .toString(),
        "-isystem",
        toolchainPaths.getSysroot()
            .resolve("usr")
            .resolve("include")
            .resolve("linux")
            .toString());
  }

  private static ImmutableList<String> getAsflags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        // Default assembler flags added by the NDK to enforce the NX (no execute) security feature.
        .add("-Xassembler", "--noexecstack")
        .addAll(targetConfiguration.getAssemblerFlags(targetConfiguration.getCompiler().getType()))
        .build();
  }

  private static ImmutableList<String> getCppflags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(getCommonIncludes(toolchainPaths))
        .addAll(getCommonPreprocessorFlags())
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(getCommonCFlags())
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .build();
  }

  private static ImmutableList<String> getCxxppflags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      CxxRuntime cxxRuntime) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(getCxxRuntimeIncludeFlags(targetConfiguration, toolchainPaths, cxxRuntime));
    flags.addAll(getCommonIncludes(toolchainPaths));
    flags.addAll(getCommonPreprocessorFlags());
    flags.addAll(getCommonFlags(targetConfiguration, toolchainPaths));
    flags.addAll(getCommonCxxFlags());
    if (targetConfiguration.getCompiler().getType() == NdkCxxPlatformCompiler.Type.GCC) {
      flags.add("-Wno-literal-suffix");
    }
    flags.addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()));
    return flags.build();
  }

  /**
   * Flags used when compiling either C or C++ sources.
   */
  private static ImmutableList<String> getCommonNdkCxxPlatformCompilerFlags() {
    return ImmutableList.of(
        // Default compiler flags provided by the NDK build makefiles.
        "-ffunction-sections",
        "-funwind-tables",
        "-fomit-frame-pointer",
        "-fno-strict-aliasing");
  }

  private static ImmutableList<String> getCflagsInternal(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(
            targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(getCommonCFlags())
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(getCommonNdkCxxPlatformCompilerFlags())
        .build();
  }

  private static ImmutableList<String> getCxxflagsInternal(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(
            targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(getCommonCxxFlags())
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(getCommonNdkCxxPlatformCompilerFlags())
        .build();
  }

  /**
   * The CPU architectures to target.
   */
  public enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    X86_64,
    MIPS,
  }

  /**
   * The build toolchain, named (including compiler version) after the target platform/arch.
   */
  public enum Toolchain {

    X86("x86"),
    X86_64("x86_64"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
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

  /**
   * Name of the target CPU architecture.
   */
  public enum TargetArch {

    X86("x86"),
    X86_64("x86_64"),
    ARM("arm"),
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

  /**
   * Name of the target CPU + ABI.
   */
  public enum TargetArchAbi {

    X86("x86"),
    X86_64("x86_64"),
    ARMEABI("armeabi"),
    ARMEABI_V7A("armeabi-v7a"),
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

  /**
   * The OS and Architecture that we're building on.
   */
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

  /**
   * The C/C++ runtime library to link against.
   */
  public enum CxxRuntime {

    SYSTEM("system", "system", "system"),
    GABIXX("gabi++", "gabi++_shared", "gabi++_static"),
    STLPORT("stlport", "stlport_shared", "stlport_static"),
    GNUSTL("gnu-libstdc++", "gnustl_shared", "gnustl_static"),
    LIBCXX("llvm-libc++", "c++_shared", "c++_static"),
    ;

    private final String name;
    private final String sharedName;
    private final String staticName;

    /**
     * @param name the runtimes directory name in the NDK.
     * @param sharedName the shared library name used for this runtime.
     * @param staticName the the static library used for this runtime.
     */
    CxxRuntime(String name, String sharedName, String staticName) {
        this.name = name;
        this.sharedName = sharedName;
        this.staticName = staticName;
    }

    public String getName() {
      return name;
    }

    public String getStaticName() {
      return staticName;
    }

    public String getSharedName() {
      return sharedName;
    }

    public String getSoname() {
      return "lib" + sharedName + ".so";
    }

  }

  /**
   * The toolchains name for the platform being targeted.
   */
  public enum ToolchainTarget {

    I686_LINUX_ANDROID("i686-linux-android"),
    X86_64_LINUX_ANDROID("x86_64-linux-android"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
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
    private Map<String, Path> cachedPaths;
    private boolean strict;
    private int ndkMajorVersion;

    NdkCxxToolchainPaths(
        Path ndkRoot,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        boolean strict) {
      this(ndkRoot, readVersion(ndkRoot), targetConfiguration, hostName, strict);
    }

    NdkCxxToolchainPaths(
        Path ndkRoot,
        String ndkVersion,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        boolean strict) {
      this.cachedPaths = new HashMap<>();
      this.strict = strict;

      this.targetConfiguration = targetConfiguration;
      this.hostName = hostName;
      this.ndkRoot = ndkRoot;
      this.ndkVersion = ndkVersion;
      this.ndkMajorVersion = getNdkMajorVersion(ndkVersion);

      Assertions.assertCondition(
          ndkMajorVersion > 0,
          "Unknown ndk version: " + ndkVersion);
    }

    NdkCxxToolchainPaths getSanitizedPaths() {
      return new NdkCxxToolchainPaths(
          Paths.get(ANDROID_NDK_ROOT),
          ndkVersion,
          targetConfiguration,
          BUILD_HOST_SUBST,
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
            s = s.replace(
                "{toolchain_target}", targetConfiguration.getToolchainTarget().toString());
            s = s.replace("{compiler_version}", targetConfiguration.getCompiler().getVersion());
            s = s.replace("{compiler_type}", targetConfiguration.getCompiler().getType().getName());
            s = s.replace(
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
              result.toFile().exists(),
              result.toString() + " doesn't exist.");
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

    private Path getCxxRuntimeDirectory(CxxRuntime cxxRuntime) {
      if (cxxRuntime == CxxRuntime.GNUSTL) {
        return processPathPattern(
            "sources/cxx-stl/" + cxxRuntime.getName() + "/{gcc_compiler_version}");
      } else {
        return processPathPattern(
            "sources/cxx-stl/" + cxxRuntime.getName());
      }

    }

    private Path getCxxRuntimeLibsDirectory(CxxRuntime cxxRuntime) {
      return processPathPattern(getCxxRuntimeDirectory(cxxRuntime), "libs/{target_arch_abi}");
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
  }
}
