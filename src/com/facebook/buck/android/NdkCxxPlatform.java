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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.GnuLinker;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

/**
 * Interprets an Android NDK as a {@link CxxPlatform}, enabling our Android support to use
 * our C/C++ support.  The constructor for this class is parameterized by several configuration
 * variables to determine which build and target platforms from the NDK to use.
 */
public class NdkCxxPlatform implements CxxPlatform {

  private static final ImmutableMap<Platform, Host> BUILD_PLATFORMS =
      ImmutableMap.of(
          Platform.MACOS, Host.DARWIN_X86_64,
          Platform.LINUX, Host.LINUX_X86_64);

  private final String name;
  private final Flavor flavor;

  private final SourcePath as;
  private final ImmutableList<String> asflags;
  private final SourcePath aspp;
  private final ImmutableList<String> asppflags;
  private final SourcePath cc;
  private final ImmutableList<String> cflags;
  private final SourcePath cpp;
  private final ImmutableList<String> cppflags;
  private final SourcePath cxx;
  private final ImmutableList<String> cxxflags;
  private final SourcePath cxxpp;
  private final ImmutableList<String> cxxppflags;
  private final SourcePath cxxld;
  private final ImmutableList<String> cxxldflags;
  private final Linker ld;
  private final ImmutableList<String> ldflags;
  private final SourcePath ar;
  private final ImmutableList<String> arflags;

  public NdkCxxPlatform(
      String name,
      Flavor flavor,
      Platform platform,
      Path ndkRoot,
      TargetConfiguration targetConfiguration) {

    Preconditions.checkArgument(
        platform.equals(Platform.MACOS) || platform.equals(Platform.LINUX),
        "NDKCxxPlatform can only currently run on MacOS or Linux.");
    Host host = BUILD_PLATFORMS.get(platform);

    this.name = Preconditions.checkNotNull(name);
    this.flavor = Preconditions.checkNotNull(flavor);

    this.as = getTool(ndkRoot, targetConfiguration, host, "as");

    // Default assembler flags added by the NDK to enforce the NX (no execute) security feature.
    this.asflags = ImmutableList.of("--noexecstack");

    this.aspp = getTool(ndkRoot, targetConfiguration, host, "gcc");
    this.asppflags = ImmutableList.of();
    this.cc = getTool(ndkRoot, targetConfiguration, host, "gcc");
    this.cflags = getCflagsInternal(targetConfiguration);
    this.cpp = getTool(ndkRoot, targetConfiguration, host, "gcc");
    this.cppflags = getCppflags(ndkRoot, targetConfiguration, host);
    this.cxx = getTool(ndkRoot, targetConfiguration, host, "g++");
    this.cxxflags = getCxxflagsInternal(targetConfiguration);
    this.cxxpp = getTool(ndkRoot, targetConfiguration, host, "g++");
    this.cxxppflags = getCxxppflags(ndkRoot, targetConfiguration, host);
    this.cxxld = getTool(ndkRoot, targetConfiguration, host, "g++");
    this.cxxldflags = getCxxldflags(ndkRoot, targetConfiguration);
    this.ld = new GnuLinker(getTool(ndkRoot, targetConfiguration, host, "ld.gold"));

    // Default linker flags added by the NDK to enforce the NX (no execute) security feature.
    this.ldflags = ImmutableList.of("-z", "noexecstack");

    this.ar = getTool(ndkRoot, targetConfiguration, host, "ar");
    this.arflags = ImmutableList.of();
  }

  private static SourcePath getTool(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host,
      String tool) {
    return new PathSourcePath(ndkRoot
        .resolve("toolchains")
        .resolve(targetConfiguration.toolchain.toString())
        .resolve("prebuilt")
        .resolve(host.toString())
        .resolve(targetConfiguration.toolchainPrefix.toString())
        .resolve("bin")
        .resolve(tool));
  }

  /**
   * Flags to be used when either preprocessing or compiling C or C++ sources.
   */
  private static ImmutableList<String> getCommonFlags() {
    return ImmutableList.of(
        // Enable default warnings and turn them into errors.
        "-Wall",
        "-Werror",
        // NDK builds enable stack protector and debug symbols by default.
        "-fstack-protector",
        "-g");
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
        "-isystem", ndkRoot
            .resolve("toolchains")
            .resolve(targetConfiguration.toolchain.toString())
            .resolve("prebuilt")
            .resolve(host.toString())
            .resolve("include")
            .toString(),
        "-isystem", ndkRoot
            .resolve("toolchains")
            .resolve(targetConfiguration.toolchain.toString())
            .resolve("prebuilt")
            .resolve(host.toString())
            .resolve("lib")
            .resolve("gcc")
            .resolve(targetConfiguration.toolchainPrefix.toString())
            .resolve(targetConfiguration.compilerVersion)
            .resolve("include")
            .toString(),
        "-isystem", ndkRoot
            .resolve("platforms")
            .resolve(targetConfiguration.targetPlatform)
            .resolve("arch-" + targetConfiguration.targetArch)
            .resolve("usr")
            .resolve("include")
            .toString(),
        "-isystem", ndkRoot
            .resolve("platforms")
            .resolve(targetConfiguration.targetPlatform)
            .resolve("arch-" + targetConfiguration.targetArch)
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
        .addAll(targetConfiguration.compilerFlags)
        .addAll(getCommonPreprocessorFlags())
        .addAll(getCommonCFlags())
        .addAll(getCommonFlags())
        .addAll(getCommonIncludes(ndkRoot, targetConfiguration, host))
        .build();
  }

  private static ImmutableList<String> getCxxppflags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration,
      Host host) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.compilerFlags)
        .addAll(getCommonPreprocessorFlags())
        .addAll(getCommonCxxFlags())
        .addAll(getCommonFlags())
        .add("-Wno-literal-suffix")
        .add("-isystem",
             ndkRoot
                 .resolve("sources")
                 .resolve("cxx-stl")
                 .resolve("gnu-libstdc++")
                 .resolve(targetConfiguration.compilerVersion)
                 .resolve("include")
                 .toString())
        .add("-isystem",
             ndkRoot
                 .resolve("sources")
                 .resolve("cxx-stl")
                 .resolve("gnu-libstdc++")
                 .resolve(targetConfiguration.compilerVersion)
                 .resolve("libs")
                 .resolve(targetConfiguration.targetArchAbi.toString())
                 .resolve("include")
                 .toString())
        .addAll(getCommonIncludes(ndkRoot, targetConfiguration, host))
        .build();
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
      TargetConfiguration targetConfiguration) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.compilerFlags)
        .addAll(getCommonCFlags())
        .addAll(getCommonFlags())
        .addAll(getCommonCompilerFlags())
        .build();
  }

  private static ImmutableList<String> getCxxflagsInternal(
      TargetConfiguration targetConfiguration) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.compilerFlags)
        .addAll(getCommonCxxFlags())
        .addAll(getCommonFlags())
        .addAll(getCommonCompilerFlags())
        .build();
  }

  private static ImmutableList<String> getCxxldflags(
      Path ndkRoot,
      TargetConfiguration targetConfiguration) {
    return ImmutableList.of(
        "-B" + ndkRoot
            .resolve("platforms")
            .resolve(targetConfiguration.targetPlatform)
            .resolve("arch-" + targetConfiguration.targetArch)
            .resolve("usr")
            .resolve("lib")
            .toString());
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Flavor asFlavor() {
    return flavor;
  }

  @Override
  public SourcePath getAs() {
    return as;
  }

  @Override
  public ImmutableList<String> getAsflags() {
    return asflags;
  }

  @Override
  public SourcePath getAspp() {
    return aspp;
  }

  @Override
  public ImmutableList<String> getAsppflags() {
    return asppflags;
  }

  @Override
  public SourcePath getCc() {
    return cc;
  }

  @Override
  public ImmutableList<String> getCflags() {
    return cflags;
  }

  @Override
  public SourcePath getCxx() {
    return cxx;
  }

  @Override
  public ImmutableList<String> getCxxflags() {
    return cxxflags;
  }

  @Override
  public SourcePath getCpp() {
    return cpp;
  }

  @Override
  public ImmutableList<String> getCppflags() {
    return cppflags;
  }

  @Override
  public SourcePath getCxxpp() {
    return cxxpp;
  }

  @Override
  public ImmutableList<String> getCxxppflags() {
    return cxxppflags;
  }

  @Override
  public SourcePath getCxxld() {
    return cxxld;
  }

  @Override
  public ImmutableList<String> getCxxldflags() {
    return cxxldflags;
  }

  @Override
  public Linker getLd() {
    return ld;
  }

  @Override
  public ImmutableList<String> getLdflags() {
    return ldflags;
  }

  @Override
  public SourcePath getAr() {
    return ar;
  }

  @Override
  public ImmutableList<String> getArflags() {
    return arflags;
  }

  @Override
  public SourcePath getLex() {
    throw new HumanReadableException("lex is not supported on %s platform", getName());
  }

  @Override
  public ImmutableList<String> getLexFlags() {
    throw new HumanReadableException("lex is not supported on %s platform", getName());
  }

  @Override
  public SourcePath getYacc() {
    throw new HumanReadableException("yacc is not supported on %s platform", getName());
  }

  @Override
  public ImmutableList<String> getYaccFlags() {
    throw new HumanReadableException("yacc is not supported on %s platform", getName());
  }

  @Override
  public BuildTarget getGtestDep() {
    throw new HumanReadableException("gtest is not supported on %s platform", getName());
  }

  @Override
  public BuildTarget getBoostTestDep() {
    throw new HumanReadableException("boost is not supported on %s platform", getName());
  }

  /**
   * The build toolchain, named (including compiler version) after the target platform/arch.
   */
  public static enum Toolchain {

    X86_4_8("x86-4.8"),
    ARM_LINUX_ADNROIDEABI_4_8("arm-linux-androideabi-4.8"),
    ;

    private final String value;

    private Toolchain(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * The prefix used for tools built for the above toolchain.
   */
  public static enum ToolchainPrefix {

    I686_LINUX_ANDROID("i686-linux-android"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
    ;

    private final String value;

    private ToolchainPrefix(String value) {
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
  public static enum TargetArch {

    X86("x86"),
    ARM("arm"),
    ;

    private final String value;

    private TargetArch(String value) {
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
  public static enum TargetArchAbi {

    X86("x86"),
    ARMEABI("armeabi"),
    ARMEABI_V7A("armeabi-v7a"),
    ;

    private final String value;

    private TargetArchAbi(String value) {
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
  public static enum Host {

    DARWIN_X86_64("darwin-x86_64"),
    LINUX_X86_64("linux-x86_64"),
    ;

    private final String value;

    private Host(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * A container for all configuration settings needed to define a build target.
   */
  public static class TargetConfiguration {

    public final Toolchain toolchain;
    public final ToolchainPrefix toolchainPrefix;
    public final TargetArch targetArch;
    public final TargetArchAbi targetArchAbi;
    public final String targetPlatform;
    public final String compilerVersion;
    public final ImmutableList<String> compilerFlags;

    public TargetConfiguration(
        Toolchain toolchain,
        ToolchainPrefix toolchainPrefix,
        TargetArch targetArch,
        TargetArchAbi targetArchAbi,
        String targetPlatform,
        String compilerVersion,
        ImmutableList<String> compilerFlags) {
      this.toolchain = Preconditions.checkNotNull(toolchain);
      this.toolchainPrefix = Preconditions.checkNotNull(toolchainPrefix);
      this.targetArch = Preconditions.checkNotNull(targetArch);
      this.targetArchAbi = Preconditions.checkNotNull(targetArchAbi);
      this.targetPlatform = Preconditions.checkNotNull(targetPlatform);
      this.compilerVersion = Preconditions.checkNotNull(compilerVersion);
      this.compilerFlags = Preconditions.checkNotNull(compilerFlags);
    }

  }

}
