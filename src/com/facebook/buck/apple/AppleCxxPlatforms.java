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

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.BsdArchiver;
import com.facebook.buck.cxx.ClangCompiler;
import com.facebook.buck.cxx.ClangPreprocessor;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.DarwinLinker;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.DefaultCompiler;
import com.facebook.buck.cxx.DefaultPreprocessor;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.VersionedTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to
 * support building iOS and Mac OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() { }

  private static final Path USR_BIN = Paths.get("usr/bin");

  public static AppleCxxPlatform build(
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig) {
    return buildWithExecutableChecker(
        targetSdk,
        minVersion,
        targetArchitecture,
        sdkPaths,
        buckConfig,
        new ExecutableFinder());
  }

  @VisibleForTesting
  static AppleCxxPlatform buildWithExecutableChecker(
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      final AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<Path> toolSearchPathsBuilder = ImmutableList.builder();
    // Search for tools from most specific to least specific.
    toolSearchPathsBuilder
        .add(sdkPaths.getSdkPath().resolve(USR_BIN))
        .add(sdkPaths.getSdkPath().resolve("Developer").resolve(USR_BIN))
        .add(sdkPaths.getPlatformPath().resolve("Developer").resolve(USR_BIN));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      toolSearchPathsBuilder.add(toolchainPath.resolve(USR_BIN));
    }
    if (sdkPaths.getDeveloperPath().isPresent()) {
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve(USR_BIN));
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve("Tools"));
    }
    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    // TODO(bhamiltoncx): Add more and better cflags.
    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    cflagsBuilder.add(targetSdk.getApplePlatform().getMinVersionFlagPrefix() + minVersion);

    // Some flags are common to both asm and C.
    ImmutableList<String> asflags = cflagsBuilder.build();

    ImmutableList<String> ldflags =
        ImmutableList.copyOf(Linkers.iXlinker("-sdk_version", targetSdk.getVersion(), "-ObjC"));

    ImmutableList.Builder<String> versionsBuilder = ImmutableList.builder();
    versionsBuilder.add(targetSdk.getVersion());
    for (AppleToolchain toolchain : targetSdk.getToolchains()) {
      versionsBuilder.add(toolchain.getVersion());
    }
    String version = Joiner.on(':').join(versionsBuilder.build());

    Tool clangPath = new VersionedTool(
        getToolPath("clang", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-clang",
        version);

    Tool clangXxPath = new VersionedTool(
        getToolPath("clang++", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-clang++",
        version);

    Tool ar = new VersionedTool(
        getToolPath("ar", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-ar",
        version);

    Tool ranlib = new VersionedTool(
        getToolPath("ranlib", toolSearchPaths, executableFinder),
        ImmutableList.<String>of("-s"),
        "apple-ranlib",
        version);

    Tool strip = new VersionedTool(
        getToolPath("strip", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-strip",
        version);

    Tool nm = new VersionedTool(
        getToolPath("nm", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-nm",
        version);

    Tool actool = new VersionedTool(
        getToolPath("actool", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-actool",
        version);

    Tool ibtool = new VersionedTool(
        getToolPath("ibtool", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-ibtool",
        version);

    Tool xctest = new VersionedTool(
        getToolPath("xctest", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-xctest",
        version);

    Optional<Tool> otest = getOptionalTool(
        "otest",
        toolSearchPaths,
        executableFinder,
        version);

    Tool dsymutil = new VersionedTool(
        getToolPath("dsymutil", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-dsymutil",
        version);

    Tool lipo = new VersionedTool(
        getToolPath("lipo", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-lipo",
        version);

    Tool lldb = new VersionedTool(
        getToolPath("lldb", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "lldb",
        version);

    Optional<Path> stubBinaryPath = targetSdk.getApplePlatform().getStubBinaryPath().transform(
        new Function<Path, Path>() {
          @Override
          public Path apply(Path input) {
            return sdkPaths.getSdkPath().resolve(input);
          }
        });

    CxxBuckConfig config = new CxxBuckConfig(buckConfig);

    ImmutableFlavor targetFlavor = ImmutableFlavor.of(
        ImmutableFlavor.replaceInvalidCharacters(
            targetSdk.getName() + "-" + targetArchitecture));

    ImmutableBiMap.Builder<Path, Path> sanitizerPaths = ImmutableBiMap.builder();
    sanitizerPaths.put(sdkPaths.getSdkPath(), Paths.get("APPLE_SDKROOT"));
    sanitizerPaths.put(sdkPaths.getPlatformPath(), Paths.get("APPLE_PLATFORM_DIR"));
    if (sdkPaths.getDeveloperPath().isPresent()) {
      sanitizerPaths.put(sdkPaths.getDeveloperPath().get(), Paths.get("APPLE_DEVELOPER_DIR"));
    }

    DebugPathSanitizer debugPathSanitizer = new DebugPathSanitizer(
        250,
        File.separatorChar,
        Paths.get("."),
        sanitizerPaths.build());

    ImmutableList<String> cflags = cflagsBuilder.build();

    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkPaths.getSdkPath().toString());
    macrosBuilder.put("PLATFORM_DIR", sdkPaths.getPlatformPath().toString());
    if (sdkPaths.getDeveloperPath().isPresent()) {
      macrosBuilder.put("DEVELOPER_DIR", sdkPaths.getDeveloperPath().get().toString());
    }
    ImmutableMap<String, String> macros = macrosBuilder.build();

    Optional<String> buildVersion;
    try (InputStream versionPlist =
             Files.newInputStream(sdkPaths.getPlatformPath().resolve("version.plist"))) {
      NSDictionary versionInfo = (NSDictionary) PropertyListParser.parse(versionPlist);
      buildVersion = Optional.of(versionInfo.objectForKey("ProductBuildVersion").toString());
    } catch (Exception e) {
      buildVersion = Optional.absent();
    }

    CxxPlatform cxxPlatform = CxxPlatforms.build(
        targetFlavor,
        config,
        new DefaultCompiler(clangPath),
        new DefaultPreprocessor(clangPath),
        new ClangCompiler(clangPath),
        new ClangCompiler(clangXxPath),
        new ClangPreprocessor(clangPath),
        new ClangPreprocessor(clangXxPath),
        new DarwinLinker(clangXxPath),
        ImmutableList.<String>builder()
            .addAll(cflags)
            .addAll(ldflags)
            .build(),
        strip,
        new BsdArchiver(ar),
        ranlib,
        nm,
        asflags,
        ImmutableList.<String>of(),
        cflags,
        ImmutableList.<String>of(),
        "dylib",
        "%s.dylib",
        Optional.of(debugPathSanitizer),
        macros);

    ApplePlatform platform = targetSdk.getApplePlatform();

    ImmutableList<String> swiftParams = ImmutableList.of(
        "-frontend",
        "-sdk",
        sdkPaths.getSdkPath().toString(),
        "-target",
        targetArchitecture + "-apple-" +
        platform.getSwiftName().or(platform.getName()) + targetSdk.getVersion());

    ImmutableList<String> swiftStdlibToolParams = ImmutableList.of(
        "--copy",
        "--verbose",
        "--strip-bitcode",
        "--platform",
        platform.getName());

    AppleCxxPlatform.Builder platformBuilder = AppleCxxPlatform.builder()
        .setCxxPlatform(cxxPlatform)
        .setAppleSdk(targetSdk)
        .setAppleSdkPaths(sdkPaths)
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setIbtool(ibtool)
        .setXctest(xctest)
        .setOtest(otest)
        .setDsymutil(dsymutil)
        .setLipo(lipo)
        .setStubBinary(stubBinaryPath)
        .setLldb(lldb)
        .setCodesignAllocate(
            getOptionalTool("codesign_allocate", toolSearchPaths, executableFinder, version))
        .setSwift(
            getOptionalToolWithParams(
                "swift",
                toolSearchPaths,
                executableFinder,
                version,
                swiftParams))
        .setSwiftStdlibTool(
            getOptionalToolWithParams(
                "swift-stdlib-tool",
                toolSearchPaths,
                executableFinder,
                version,
                swiftStdlibToolParams));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      Path swiftRuntimePath = toolchainPath
          .resolve("usr/lib/swift")
          .resolve(platform.getName());
      if (Files.isDirectory(swiftRuntimePath)) {
        platformBuilder.addSwiftRuntimePaths(swiftRuntimePath);
      }
      Path swiftStaticRuntimePath = toolchainPath
          .resolve("usr/lib/swift_static")
          .resolve(platform.getName());
      if (Files.isDirectory(swiftStaticRuntimePath)) {
        platformBuilder.addSwiftStaticRuntimePaths(swiftStaticRuntimePath);
      }
    }
    return platformBuilder.build();
  }

  private static Optional<Tool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder,
      String version) {
    return getOptionalToolWithParams(
        tool,
        toolSearchPaths,
        executableFinder,
        version,
        ImmutableList.<String>of());
  }

  private static Optional<Tool> getOptionalToolWithParams(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder,
      String version,
      ImmutableList<String> params) {
    return getOptionalToolPath(tool, toolSearchPaths, executableFinder)
        .transform(VersionedTool.fromPathWithParams(tool, version, params))
        .transform(Functions.<Tool>identity());
  }

  private static Path getToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {
    Optional<Path> result = getOptionalToolPath(tool, toolSearchPaths, executableFinder);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

    private static Optional<Path> getOptionalToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {

      return executableFinder.getOptionalExecutable(
          Paths.get(tool),
          toolSearchPaths,
          ImmutableSet.<String>of());
  }

}
