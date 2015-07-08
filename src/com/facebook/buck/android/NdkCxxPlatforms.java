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

import com.facebook.buck.cxx.ClangCompiler;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.DefaultCompiler;
import com.facebook.buck.cxx.GnuArchiver;
import com.facebook.buck.cxx.GnuLinker;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.VersionedTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

@Value.Enclosing
@BuckStyleImmutable
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

  public static final NdkCxxPlatforms.Compiler.Type DEFAULT_COMPILER_TYPE =
      NdkCxxPlatforms.Compiler.Type.GCC;
  public static final String DEFAULT_GCC_VERSION = "4.8";
  public static final String DEFAULT_CLANG_VERSION = "3.4";
  public static final String DEFAULT_TARGET_APP_PLATFORM = "android-9";
  public static final NdkCxxPlatforms.CxxRuntime DEFAULT_CXX_RUNTIME =
      NdkCxxPlatforms.CxxRuntime.GNUSTL;

  private static final ImmutableMap<Platform, Host> BUILD_PLATFORMS =
      ImmutableMap.of(
          Platform.LINUX, Host.LINUX_X86_64,
          Platform.MACOS, Host.DARWIN_X86_64,
          Platform.WINDOWS, Host.WINDOWS_X86_64);

  // Utility class, do not instantiate.
  private NdkCxxPlatforms() { }

  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      ProjectFilesystem ndkRoot,
      Compiler compiler,
      CxxRuntime cxxRuntime,
      String androidPlatform,
      Platform platform) {
    return getPlatforms(
        ndkRoot,
        compiler,
        cxxRuntime,
        androidPlatform,
        platform,
        new ExecutableFinder());
  }

  /**
   * @return the map holding the available {@link NdkCxxPlatform}s.
   */
  public static ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      ProjectFilesystem ndkRoot,
      Compiler compiler,
      CxxRuntime cxxRuntime,
      String androidPlatform,
      Platform platform,
      ExecutableFinder executableFinder) {

    ImmutableMap.Builder<TargetCpuType, NdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();

    NdkCxxPlatform armeabi =
        build(
            ImmutableFlavor.of("android-arm"),
            platform,
            ndkRoot,
            ImmutableNdkCxxPlatforms.TargetConfiguration.builder()
                .setToolchain(Toolchain.ARM_LINUX_ADNROIDEABI)
                .setTargetArch(TargetArch.ARM)
                .setTargetArchAbi(TargetArchAbi.ARMEABI)
                .setTargetAppPlatform(androidPlatform)
                .setCompiler(compiler)
                .setToolchainTarget(ToolchainTarget.ARM_LINUX_ANDROIDEABI)
                .putCompilerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.of(
                        "-march=armv5te",
                        "-mtune=xscale",
                        "-msoft-float",
                        "-mthumb",
                        "-Os"))
                .putCompilerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "armv5te-none-linux-androideabi",
                        "-march=armv5te",
                        "-mtune=xscale",
                        "-msoft-float",
                        "-mthumb",
                        "-Os"))
                .putLinkerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.of(
                        "-march=armv5te",
                        "-Wl,--fix-cortex-a8"))
                .putLinkerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "armv5te-none-linux-androideabi",
                        "-march=armv5te",
                        "-Wl,--fix-cortex-a8"))
                .build(),
            cxxRuntime,
            executableFinder);
    ndkCxxPlatformBuilder.put(TargetCpuType.ARM, armeabi);
    NdkCxxPlatform armeabiv7 =
        build(
            ImmutableFlavor.of("android-armv7"),
            platform,
            ndkRoot,
            ImmutableNdkCxxPlatforms.TargetConfiguration.builder()
                .setToolchain(Toolchain.ARM_LINUX_ADNROIDEABI)
                .setTargetArch(TargetArch.ARM)
                .setTargetArchAbi(TargetArchAbi.ARMEABI_V7A)
                .setTargetAppPlatform(androidPlatform)
                .setCompiler(compiler)
                .setToolchainTarget(ToolchainTarget.ARM_LINUX_ANDROIDEABI)
                .putCompilerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.of(
                        "-finline-limit=64",
                        "-march=armv7-a",
                        "-mfpu=vfpv3-d16",
                        "-mfloat-abi=softfp",
                        "-mthumb",
                        "-Os"))
                .putCompilerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "armv7-none-linux-androideabi",
                        "-finline-limit=64",
                        "-march=armv7-a",
                        "-mfpu=vfpv3-d16",
                        "-mfloat-abi=softfp",
                        "-mthumb",
                        "-Os"))
                .putLinkerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.<String>of())
                .putLinkerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "armv7-none-linux-androideabi"))
                .build(),
            cxxRuntime,
            executableFinder);
    ndkCxxPlatformBuilder.put(TargetCpuType.ARMV7, armeabiv7);
    NdkCxxPlatform x86 =
        build(
            ImmutableFlavor.of("android-x86"),
            platform,
            ndkRoot,
            ImmutableNdkCxxPlatforms.TargetConfiguration.builder()
                .setToolchain(Toolchain.X86)
                .setTargetArch(TargetArch.X86)
                .setTargetArchAbi(TargetArchAbi.X86)
                .setTargetAppPlatform(androidPlatform)
                .setCompiler(compiler)
                .setToolchainTarget(ToolchainTarget.I686_LINUX_ANDROID)
                .putCompilerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.of(
                        "-funswitch-loops",
                        "-finline-limit=300",
                        "-O2"))
                .putCompilerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "i686-none-linux-android",
                        "-funswitch-loops",
                        "-finline-limit=300",
                        "-O2"))
                .putLinkerFlags(
                    Compiler.Type.GCC,
                    ImmutableList.<String>of())
                .putLinkerFlags(
                    Compiler.Type.CLANG,
                    ImmutableList.of(
                        "-target", "i686-none-linux-android"))
                .build(),
            cxxRuntime,
            executableFinder);
    ndkCxxPlatformBuilder.put(TargetCpuType.X86, x86);

    return ndkCxxPlatformBuilder.build();
  }

  @VisibleForTesting
  static NdkCxxPlatform build(
      Flavor flavor,
      Platform platform,
      ProjectFilesystem ndk,
      TargetConfiguration targetConfiguration,
      CxxRuntime cxxRuntime,
      ExecutableFinder executableFinder) {

    Path ndkRoot = ndk.getRootPath();

    // Create a version string to use when generating rule keys via the NDK tools we'll generate
    // below.  This will be used in lieu of hashing the contents of the tools, so that builds from
    // different host platforms (which produce identical output) will share the cache with one
    // another.
    Compiler.Type compilerType = targetConfiguration.getCompiler().getType();
    String version =
        Joiner.on('-').join(
            ImmutableList.of(
                readVersion(ndk),
                targetConfiguration.getToolchain(),
                targetConfiguration.getTargetAppPlatform(),
                compilerType,
                targetConfiguration.getCompiler().getVersion(),
                targetConfiguration.getCompiler().getGccVersion(),
                cxxRuntime));

    Host host = Preconditions.checkNotNull(BUILD_PLATFORMS.get(platform));

    // Build up the map of paths that must be sanitized.
    ImmutableBiMap.Builder<Path, Path> sanitizePaths = ImmutableBiMap.builder();
    sanitizePaths.put(
        getNdkToolRoot(ndkRoot, targetConfiguration, host.toString()),
        getNdkToolRoot(Paths.get(ANDROID_NDK_ROOT), targetConfiguration, BUILD_HOST_SUBST));
    if (compilerType != Compiler.Type.GCC) {
        sanitizePaths.put(
            getNdkGccToolRoot(ndkRoot, targetConfiguration, host.toString()),
            getNdkGccToolRoot(Paths.get(ANDROID_NDK_ROOT), targetConfiguration, BUILD_HOST_SUBST));
    }
    sanitizePaths.put(
        ndkRoot,
        Paths.get(ANDROID_NDK_ROOT));

    CxxPlatform.Builder cxxPlatformBuilder = CxxPlatform.builder();
    cxxPlatformBuilder
        .setFlavor(flavor)
        .setAs(getGccTool(ndkRoot, targetConfiguration, host, "as", version, executableFinder))
        // Default assembler flags added by the NDK to enforce the NX (no execute) security feature.
        .addAsflags("-Xassembler", "--noexecstack")
        .setAspp(
            getCTool(
                ndkRoot,
                targetConfiguration,
                host,
                compilerType.getCc(),
                version,
                executableFinder))
        .setCc(
            compilerType.fromTool(
                getCTool(
                    ndkRoot,
                    targetConfiguration,
                    host,
                    compilerType.getCc(),
                    version,
                    executableFinder)))
        .addAllCflags(getCflagsInternal(ndkRoot, targetConfiguration, host))
        .setCpp(
            getCTool(
                ndkRoot,
                targetConfiguration,
                host,
                compilerType.getCc(),
                version,
                executableFinder))
        .addAllCppflags(getCppflags(ndkRoot, targetConfiguration, host))
        .setCxx(
            compilerType.fromTool(
                getCTool(
                    ndkRoot,
                    targetConfiguration,
                    host,
                    compilerType.getCxx(),
                    version,
                    executableFinder)))
        .addAllCxxflags(getCxxflagsInternal(ndkRoot, targetConfiguration, host))
        .setCxxpp(
            getCTool(
                ndkRoot,
                targetConfiguration,
                host,
                compilerType.getCxx(),
                version,
                executableFinder))
        .addAllCxxppflags(getCxxppflags(ndkRoot, targetConfiguration, host, cxxRuntime))
        .setCxxld(
            getCcLinkTool(
                ndkRoot,
                targetConfiguration,
                host,
                cxxRuntime,
                compilerType.getCxx(),
                version,
                executableFinder))
        .addAllCxxldflags(
            targetConfiguration.getLinkerFlags(compilerType))
        .setLd(
            new GnuLinker(
                getGccTool(
                    ndkRoot,
                    targetConfiguration,
                    host,
                    "ld.gold",
                    version,
                    executableFinder)))
        // Default linker flags added by the NDK
        .addLdflags(
            // Add a deterministic build ID to Android builds.
            // We use it to find symbols from arbitrary binaries.
            "--build-id",
            // Enforce the NX (no execute) security feature
            "-z", "noexecstack",
            // Strip unused code
            "--gc-sections",
            // Refuse to produce dynamic objects with undefined symbols
            "-z", "defs",
            // Forbid dangerous copy "relocations"
            "-z", "nocopyreloc",
            // We always pass the runtime library on the command line, so setting this flag
            // means the resulting link will only use it if it was actually needed it.
            "--as-needed")
        .setStrip(
            getGccTool(ndkRoot, targetConfiguration, host, "strip", version, executableFinder))
        .setAr(
            new GnuArchiver(
                getGccTool(ndkRoot, targetConfiguration, host, "ar", version, executableFinder)))
        // NDK builds are cross compiled, so the header is the same regardless of the host platform.
        .setDebugPathSanitizer(
            new DebugPathSanitizer(
                250,
                File.separatorChar,
                Paths.get("."),
                sanitizePaths.build()))
        .setSharedLibraryExtension("so");

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
            getCxxRuntimeLibsDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .resolve(cxxRuntime.getSoname()))
        .build();
  }

  // Read the NDK version from the "RELEASE.TXT" at the NDK root.
  private static String readVersion(ProjectFilesystem ndkRoot) {
    try (InputStream input = ndkRoot.newFileInputStream(Paths.get("RELEASE.TXT"));
         BufferedReader reader = new BufferedReader(new InputStreamReader(input, Charsets.UTF_8))) {
        return reader.readLine().trim();
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "could not extract version from NDK repository at %s: %s",
          ndkRoot,
          e.getMessage());
    }
  }

  /**
   * @return the path to use as the system root, targeted to the given target platform and
   *     architecture.
   */
  private static Path getSysroot(
      Path ndkRoot,
      TargetConfiguration targetConfiguration) {
    return ndkRoot
        .resolve("platforms")
        .resolve(targetConfiguration.getTargetAppPlatform())
        .resolve("arch-" + targetConfiguration.getTargetArch());
  }

  private static Path getNdkGccToolRoot(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      String hostName) {
    return ndkRoot
        .resolve("toolchains")
        .resolve(
            String.format(
                "%s-%s",
                targetConfiguration.getToolchain(),
                targetConfiguration.getCompiler().getGccVersion()))
        .resolve("prebuilt")
        .resolve(hostName);
  }

  private static Path getNdkToolRoot(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      String hostName) {
    return ndkRoot
        .resolve("toolchains")
        .resolve(
            String.format(
                "%s-%s",
                targetConfiguration.getCompiler().getType() == Compiler.Type.CLANG ?
                    "llvm" :
                    targetConfiguration.getToolchain(),
                targetConfiguration.getCompiler().getVersion()))
        .resolve("prebuilt")
        .resolve(hostName);
  }

  private static Path getLibexecGccToolPath(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return getNdkToolRoot(ndkRoot, targetConfiguration, host.toString())
        .resolve("libexec")
        .resolve("gcc")
        .resolve(targetConfiguration.getToolchain().toString())
        .resolve(targetConfiguration.getCompiler().getVersion());
  }

  private static Path getToolchainBinPath(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return getNdkToolRoot(ndkRoot, targetConfiguration, host.toString())
        .resolve(
            targetConfiguration.getCompiler().getType() == Compiler.Type.GCC ?
                targetConfiguration.getToolchainTarget().toString() :
                "")
        .resolve("bin");
  }

  private static Path getToolPath(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      String tool,
      ExecutableFinder executableFinder) {
    Path expected =
        getToolchainBinPath(ndkRoot, targetConfiguration, host)
            .resolve(tool);
    Optional<Path> path =
        executableFinder.getOptionalExecutable(expected, ImmutableMap.<String, String>of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Path getGccToolchainBinPath(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return getNdkGccToolRoot(ndkRoot, targetConfiguration, host.toString())
        .resolve(targetConfiguration.getToolchainTarget().toString())
        .resolve("bin");
  }

  private static Path getGccToolPath(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      String tool,
      ExecutableFinder executableFinder) {
    Path expected =
        getGccToolchainBinPath(ndkRoot, targetConfiguration, host)
            .resolve(tool);
    Optional<Path> path =
        executableFinder.getOptionalExecutable(expected, ImmutableMap.<String, String>of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return path.get();
  }

  private static Tool getGccTool(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return new VersionedTool(
        getGccToolPath(ndkRoot, targetConfiguration, host, tool, executableFinder),
        ImmutableList.<String>of(),
        tool,
        version);
  }

  private static Tool getCTool(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return new VersionedTool(
        getToolPath(ndkRoot, targetConfiguration, host, tool, executableFinder),
        ImmutableList.<String>of(),
        tool,
        version);
  }

  private static Path getCxxRuntimeDirectory(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      CxxRuntime cxxRuntime) {
    return ndkRoot
        .resolve("sources")
        .resolve("cxx-stl")
        .resolve(cxxRuntime.getName())
        .resolve(
            cxxRuntime == CxxRuntime.GNUSTL ?
                targetConfiguration.getCompiler().getGccVersion() :
                "");
  }

  private static ImmutableList<String> getCxxRuntimeIncludeFlags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      CxxRuntime cxxRuntime) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    switch (cxxRuntime) {
      case GNUSTL:
        flags.add(
            "-isystem",
            getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .resolve("libs")
                .resolve(targetConfiguration.getTargetArchAbi().toString())
                .resolve("include")
                .toString());
        break;
      case LIBCXX:
        flags.add(
            "-isystem",
            getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .resolve("libcxx")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .getParent()
                .resolve("llvm-libc++abi")
                .resolve("libcxxabi")
                .resolve("include")
                .toString());
        flags.add(
            "-isystem",
            ndkRoot
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
            getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
                .resolve("include")
                .toString());
    }
    return flags.build();
  }

  private static Path getCxxRuntimeLibsDirectory(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      CxxRuntime cxxRuntime) {
    return getCxxRuntimeDirectory(ndkRoot, targetConfiguration, cxxRuntime)
        .resolve("libs")
        .resolve(targetConfiguration.getTargetArchAbi().toString());
  }

  private static Linker getCcLinkTool(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      CxxRuntime cxxRuntime,
      String tool,
      String version,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find GCC tools.
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.CLANG) {
      flags.add(
          "-gcc-toolchain",
          getNdkGccToolRoot(ndkRoot, targetConfiguration, host.toString()).toString());
    }

    // Set the sysroot to the platform-specific path.
    flags.add("--sysroot=" + getSysroot(ndkRoot, targetConfiguration));

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.GCC) {
      flags.add(
          "-B" + getLibexecGccToolPath(ndkRoot, targetConfiguration, host),
          "-B" + getNdkToolRoot(ndkRoot, targetConfiguration, host.toString())
              .resolve("lib")
              .resolve(targetConfiguration.getCompiler().getType().getName())
              .resolve(
                  targetConfiguration.getCompiler().getType() == Compiler.Type.GCC ?
                      targetConfiguration.getToolchainTarget().toString() :
                      "")
              .resolve(targetConfiguration.getCompiler().getVersion()));
    }

    // Add the path to the C/C++ runtime libraries.
    flags.add(
        "-L" + getCxxRuntimeLibsDirectory(ndkRoot, targetConfiguration, cxxRuntime).toString());

    return new GnuLinker(
        new VersionedTool(
            getToolPath(ndkRoot, targetConfiguration, host, tool, executableFinder),
            flags.build(),
            tool,
            version));
  }

  /**
   * Flags to be used when either preprocessing or compiling C or C++ sources.
   */
  private static ImmutableList<String> getCommonFlags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find the GCC tools.
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.CLANG) {
      flags.add(
          "-gcc-toolchain",
          getNdkGccToolRoot(ndkRoot, targetConfiguration, host.toString()).toString());
    }

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.GCC) {
      flags.add(
          "-B" + getLibexecGccToolPath(ndkRoot, targetConfiguration, host),
          "-B" + getToolchainBinPath(ndkRoot, targetConfiguration, host));
    }

    // Enable default warnings and turn them into errors.
    flags.add(
        "-Wall",
        "-Werror");

    // NOTE:  We pass all compiler flags to the preprocessor to make sure any necessary internal
    // macros get defined and we also pass the include paths to the to the compiler since we're
    // not whether we're doing combined preprocessing/compiling or not.
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.CLANG) {
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
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return ImmutableList.of(
        "-isystem",
        getNdkToolRoot(ndkRoot, targetConfiguration, host.toString())
            .resolve("include")
            .toString(),
        "-isystem",
        getNdkToolRoot(ndkRoot, targetConfiguration, host.toString())
            .resolve("lib")
            .resolve(targetConfiguration.getCompiler().getType().getName())
            .resolve(
                targetConfiguration.getCompiler().getType() == Compiler.Type.GCC ?
                    targetConfiguration.getToolchainTarget().toString() :
                    "")
            .resolve(targetConfiguration.getCompiler().getVersion())
            .resolve("include")
            .toString(),
        "-isystem",
        getSysroot(ndkRoot, targetConfiguration)
            .resolve("usr")
            .resolve("include")
            .toString(),
        "-isystem",
        getSysroot(ndkRoot, targetConfiguration)
            .resolve("usr")
            .resolve("include")
            .resolve("linux")
            .toString());
  }

  private static ImmutableList<String> getCppflags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return ImmutableList.<String>builder()
        .addAll(getCommonIncludes(ndkRoot, targetConfiguration, host))
        .addAll(getCommonPreprocessorFlags())
        .addAll(getCommonFlags(ndkRoot, targetConfiguration, host))
        .addAll(getCommonCFlags())
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .build();
  }

  private static ImmutableList<String> getCxxppflags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      CxxRuntime cxxRuntime) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(getCxxRuntimeIncludeFlags(ndkRoot, targetConfiguration, cxxRuntime));
    flags.addAll(getCommonIncludes(ndkRoot, targetConfiguration, host));
    flags.addAll(getCommonPreprocessorFlags());
    flags.addAll(getCommonFlags(ndkRoot, targetConfiguration, host));
    flags.addAll(getCommonCxxFlags());
    if (targetConfiguration.getCompiler().getType() == Compiler.Type.GCC) {
      flags.add("-Wno-literal-suffix");
    }
    flags.addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()));
    return flags.build();
  }

  /**
   * Flags used when compiling either C or C++ sources.
   */
  private static ImmutableList<String> getCommonCompilerFlags() {
    return ImmutableList.of(
        // Default compiler flags provided by the NDK build makefiles.
        "-ffunction-sections",
        "-funwind-tables",
        "-fomit-frame-pointer",
        "-fno-strict-aliasing");
  }

  private static ImmutableList<String> getCflagsInternal(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return ImmutableList.<String>builder()
        .addAll(
            targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(getCommonCFlags())
        .addAll(getCommonFlags(ndkRoot, targetConfiguration, host))
        .addAll(getCommonCompilerFlags())
        .build();
  }

  private static ImmutableList<String> getCxxflagsInternal(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return ImmutableList.<String>builder()
        .addAll(
            targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(getCommonCxxFlags())
        .addAll(getCommonFlags(ndkRoot, targetConfiguration, host))
        .addAll(getCommonCompilerFlags())
        .build();
  }

  /**
   * The CPU architectures to target.
   */
  public enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    MIPS,
  }

  /**
   * The build toolchain, named (including compiler version) after the target platform/arch.
   */
  public enum Toolchain {

    X86("x86"),
    ARM_LINUX_ADNROIDEABI("arm-linux-androideabi"),
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

  @Value.Immutable
  public interface Compiler {

    Type getType();

    /**
     * @return the compiler version, corresponding to either `gcc_version` or `clang_version`
     *     from the .buckconfig settings, depending on which compiler family was selected.
     */
    String getVersion();

    /**
     *
     * @return the GCC compiler version.  Since even runtimes which are not GCC-based need to use
     *     GCC tools (e.g. ar, as,, ld.gold), we need to *always* have a version of GCC.
     */
    String getGccVersion();

    enum Type {

      GCC("gcc", "gcc", "g++"),
      CLANG("clang", "clang", "clang++"),
      ;

      private final String name;
      private final String cc;
      private final String cxx;

      Type(String name, String cc, String cxx) {
        this.name = name;
        this.cc = cc;
        this.cxx = cxx;
      }

      public String getName() {
        return name;
      }

      public String getCc() {
        return cc;
      }

      public String getCxx() {
        return cxx;
      }

      public com.facebook.buck.cxx.Compiler fromTool(Tool tool) {
        switch (this) {
          case GCC:
            return new DefaultCompiler(tool);
          case CLANG:
            return new ClangCompiler(tool);
        }
        throw new RuntimeException("Invalid compiler type");
      }

    }

  }

  /**
   * A container for all configuration settings needed to define a build target.
   */
  @Value.Immutable
  public abstract static class TargetConfiguration {

    public abstract Toolchain getToolchain();
    public abstract ToolchainTarget getToolchainTarget();
    public abstract TargetArch getTargetArch();
    public abstract TargetArchAbi getTargetArchAbi();
    public abstract String getTargetAppPlatform();
    public abstract Compiler getCompiler();
    public abstract ImmutableMap<Compiler.Type, ImmutableList<String>> getCompilerFlags();
    public abstract ImmutableMap<Compiler.Type, ImmutableList<String>> getLinkerFlags();

    public ImmutableList<String> getCompilerFlags(Compiler.Type type) {
      return Optional.fromNullable(getCompilerFlags().get(type)).or(ImmutableList.<String>of());
    }

    public ImmutableList<String> getLinkerFlags(Compiler.Type type) {
      return Optional.fromNullable(getLinkerFlags().get(type)).or(ImmutableList.<String>of());
    }

  }

}
