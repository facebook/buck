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
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.BsdArchiver;
import com.facebook.buck.cxx.CompilerProvider;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxToolProvider;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.PreprocessorProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.swift.SwiftPlatform;
import com.facebook.buck.swift.SwiftPlatforms;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to
 * support building iOS and Mac OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  private static final Logger LOG = Logger.get(AppleCxxPlatforms.class);

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() { }

  private static final Path USR_BIN = Paths.get("usr/bin");

  public static AppleCxxPlatform build(
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      AppleConfig appleConfig,
      Optional<ProcessExecutor> processExecutor,
      Optional<AppleToolchain> swiftToolChain) {
    return buildWithExecutableChecker(
        targetSdk,
        minVersion,
        targetArchitecture,
        sdkPaths,
        buckConfig,
        appleConfig,
        new ExecutableFinder(),
        processExecutor,
        swiftToolChain);
  }

  @VisibleForTesting
  static AppleCxxPlatform buildWithExecutableChecker(
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      final AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      AppleConfig appleConfig,
      ExecutableFinder executableFinder,
      Optional<ProcessExecutor> processExecutor,
      Optional<AppleToolchain> swiftToolChain) {
    AppleCxxPlatform.Builder platformBuilder = AppleCxxPlatform.builder();

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

    // TODO(bhamiltoncx): Add more and better cflags.
    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    cflagsBuilder.add(targetSdk.getApplePlatform().getMinVersionFlagPrefix() + minVersion);

    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      cflagsBuilder.add("-fembed-bitcode");
    }

    ImmutableList.Builder<String> ldflagsBuilder = ImmutableList.builder();
    ldflagsBuilder.addAll(Linkers.iXlinker("-sdk_version", targetSdk.getVersion(), "-ObjC"));
    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      ldflagsBuilder.addAll(
          Linkers.iXlinker("-bitcode_verify", "-bitcode_hide_symbols", "-bitcode_symbol_map"));
    }


    // Populate Xcode version keys from Xcode's own Info.plist if available.
    Optional<String> xcodeBuildVersion = Optional.absent();
    Optional<Path> developerPath = sdkPaths.getDeveloperPath();
    if (developerPath.isPresent()) {
      Path xcodeBundlePath = developerPath.get().getParent();
      if (xcodeBundlePath != null) {
        File xcodeInfoPlistPath = xcodeBundlePath.resolve("Info.plist").toFile();
        try {
          NSDictionary parsedXcodeInfoPlist =
              (NSDictionary) PropertyListParser.parse(xcodeInfoPlistPath);

          NSObject xcodeVersionObject = parsedXcodeInfoPlist.objectForKey("DTXcode");
          if (xcodeVersionObject != null) {
            Optional<String> xcodeVersion = Optional.of(xcodeVersionObject.toString());
            platformBuilder.setXcodeVersion(xcodeVersion);
          }
        } catch (IOException e) {
          LOG.debug(
              "Error reading Xcode's info plist %s; ignoring Xcode versions",
              xcodeInfoPlistPath);
        } catch (
            PropertyListFormatException |
                ParseException |
                ParserConfigurationException |
                SAXException e) {
          LOG.debug("Error in parsing %s; ignoring Xcode versions", xcodeInfoPlistPath);
        }
      }

      // Get the Xcode build version as reported by `xcodebuild -version`.  This is
      // different than the build number in the Info.plist, sigh.
      if (processExecutor.isPresent()) {
        try {
          xcodeBuildVersion = appleConfig
              .getXcodeBuildVersionSupplier(developerPath.get(), processExecutor.get())
              .get();
          platformBuilder.setXcodeBuildVersion(xcodeBuildVersion);
          LOG.debug("Xcode build version is: " + xcodeBuildVersion.or("<absent>"));
        } catch (IOException e) {
          LOG.debug("Error in getting Xcode build version");
        }
      }
    }

    ImmutableList.Builder<String> versions = ImmutableList.builder();
    versions.add(targetSdk.getVersion());

    ImmutableList<String> toolchainVersions = ImmutableList.copyOf(
        Optional.presentInstances(
            Iterables.transform(
                targetSdk.getToolchains(),
                new Function<AppleToolchain, Optional<String>>() {
                  @Override
                  public Optional<String> apply(AppleToolchain input) {
                    return input.getVersion();
                  }
                })));
    if (toolchainVersions.isEmpty()) {
      if (!xcodeBuildVersion.isPresent()) {
        throw new HumanReadableException("Failed to read toolchain versions and Xcode version.");
      }
      versions.add(xcodeBuildVersion.get());
    } else {
      versions.addAll(toolchainVersions);
    }

    String version = Joiner.on(':').join(versions.build());

    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    Tool clangPath = VersionedTool.of(
        getToolPath("clang", toolSearchPaths, executableFinder),
        "apple-clang",
        version);

    Tool clangXxPath = VersionedTool.of(
        getToolPath("clang++", toolSearchPaths, executableFinder),
        "apple-clang++",
        version);

    Tool ar = VersionedTool.of(
        getToolPath("ar", toolSearchPaths, executableFinder),
        "apple-ar",
        version);

    Tool ranlib = VersionedTool.builder()
        .setPath(getToolPath("ranlib", toolSearchPaths, executableFinder))
        .setName("apple-ranlib")
        .setVersion(version)
        .build();

    Tool strip = VersionedTool.of(
        getToolPath("strip", toolSearchPaths, executableFinder),
        "apple-strip",
        version);

    Tool nm = VersionedTool.of(
        getToolPath("nm", toolSearchPaths, executableFinder),
        "apple-nm",
        version);

    Tool actool = VersionedTool.of(
        getToolPath("actool", toolSearchPaths, executableFinder),
        "apple-actool",
        version);

    Tool ibtool = VersionedTool.of(
        getToolPath("ibtool", toolSearchPaths, executableFinder),
        "apple-ibtool",
        version);

    Tool momc = VersionedTool.of(
        getToolPath("momc", toolSearchPaths, executableFinder),
        "apple-momc",
        version);

    Tool xctest = VersionedTool.of(
        getToolPath("xctest", toolSearchPaths, executableFinder),
        "apple-xctest",
        version);

    Tool dsymutil = VersionedTool.of(
        getToolPath("dsymutil", toolSearchPaths, executableFinder),
        "apple-dsymutil",
        version);

    Tool lipo = VersionedTool.of(
        getToolPath("lipo", toolSearchPaths, executableFinder),
        "apple-lipo",
        version);

    Tool lldb = VersionedTool.of(
        getToolPath("lldb", toolSearchPaths, executableFinder),
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
        config.getDebugPathSanitizerLimit(),
        File.separatorChar,
        Paths.get("."),
        sanitizerPaths.build());

    ImmutableList<String> cflags = cflagsBuilder.build();

    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkPaths.getSdkPath().toString());
    macrosBuilder.put("PLATFORM_DIR", sdkPaths.getPlatformPath().toString());
    macrosBuilder.put("CURRENT_ARCH", targetArchitecture);
    if (sdkPaths.getDeveloperPath().isPresent()) {
      macrosBuilder.put("DEVELOPER_DIR", sdkPaths.getDeveloperPath().get().toString());
    }
    ImmutableMap<String, String> macros = macrosBuilder.build();

    Optional<String> buildVersion;
    Path platformVersionPlistPath = sdkPaths.getPlatformPath().resolve("version.plist");
    try (InputStream versionPlist = Files.newInputStream(platformVersionPlistPath)) {
      NSDictionary versionInfo = (NSDictionary) PropertyListParser.parse(versionPlist);
      try {
        buildVersion = Optional.of(versionInfo.objectForKey("ProductBuildVersion").toString());
      } catch (NullPointerException e) {
        LOG.warn(
            "In %s, missing ProductBuildVersion. Build version will be unset for this platform.",
            platformVersionPlistPath);
        buildVersion = Optional.absent();
      }
    } catch (NoSuchFileException e) {
      LOG.warn(
          "%s does not exist. Build version will be unset for this platform.",
          platformVersionPlistPath);
      buildVersion = Optional.absent();
    } catch (PropertyListFormatException | SAXException | ParserConfigurationException |
        ParseException | IOException e) {
      // Some other error occurred, print the exception since it may contain error details.
      LOG.warn(
          e,
          "Failed to parse %s. Build version will be unset for this platform.",
          platformVersionPlistPath);
      buildVersion = Optional.absent();
    }

    PreprocessorProvider aspp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider as =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    PreprocessorProvider cpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider cc =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    PreprocessorProvider cxxpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider cxx =
        new CompilerProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG);

    CxxPlatform cxxPlatform = CxxPlatforms.build(
        targetFlavor,
        config,
        as,
        aspp,
        cc,
        cxx,
        cpp,
        cxxpp,
        new DefaultLinkerProvider(
            LinkerProvider.Type.DARWIN,
            new ConstantToolProvider(clangXxPath)),
        ImmutableList.<String>builder()
            .addAll(cflags)
            .addAll(ldflagsBuilder.build())
            .build(),
        strip,
        new BsdArchiver(ar),
        ranlib,
        new PosixNmSymbolNameTool(nm),
        cflagsBuilder.build(),
        ImmutableList.<String>of(),
        cflags,
        ImmutableList.<String>of(),
        "dylib",
        "%s.dylib",
        "a",
        "o",
        Optional.of(debugPathSanitizer),
        macros);

    ApplePlatform applePlatform = targetSdk.getApplePlatform();
    ImmutableList.Builder<Path> swiftOverrideSearchPathBuilder = ImmutableList.builder();
    AppleSdkPaths.Builder swiftSdkPathsBuilder = AppleSdkPaths.builder().from(sdkPaths);
    if (swiftToolChain.isPresent()) {
      swiftOverrideSearchPathBuilder.add(swiftToolChain.get().getPath().resolve(USR_BIN));
      swiftSdkPathsBuilder.setToolchainPaths(ImmutableList.of(swiftToolChain.get().getPath()));
    }
    Optional<SwiftPlatform> swiftPlatform = getSwiftPlatform(
        applePlatform.getName(),
        targetArchitecture + "-apple-" +
            applePlatform.getSwiftName().or(applePlatform.getName()) + targetSdk.getVersion(),
        version,
        swiftSdkPathsBuilder.build(),
        swiftOverrideSearchPathBuilder
            .addAll(toolSearchPaths)
            .build(),
        executableFinder);

    platformBuilder
        .setCxxPlatform(cxxPlatform)
        .setSwiftPlatform(swiftPlatform)
        .setAppleSdk(targetSdk)
        .setAppleSdkPaths(sdkPaths)
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setIbtool(ibtool)
        .setMomc(momc)
        .setXctest(xctest)
        .setDsymutil(dsymutil)
        .setLipo(lipo)
        .setStubBinary(stubBinaryPath)
        .setLldb(lldb)
        .setCodesignAllocate(
            getOptionalTool("codesign_allocate", toolSearchPaths, executableFinder, version));

    return platformBuilder.build();
  }

  private static Optional<SwiftPlatform> getSwiftPlatform(
      String platformName,
      String targetArchitectureName,
      String version,
      AbstractAppleSdkPaths sdkPaths,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {
    ImmutableList<String> swiftParams = ImmutableList.of(
        "-frontend",
        "-sdk",
        sdkPaths.getSdkPath().toString(),
        "-target",
        targetArchitectureName);

    ImmutableList<String> swiftStdlibToolParams = ImmutableList.of(
        "--copy",
        "--verbose",
        "--strip-bitcode",
        "--platform",
        platformName);

    Optional<Tool> swift = getOptionalToolWithParams(
        "swift",
        toolSearchPaths,
        executableFinder,
        version,
        swiftParams);
    Optional<Tool> swiftStdLibTool = getOptionalToolWithParams(
        "swift-stdlib-tool",
        toolSearchPaths,
        executableFinder,
        version,
        swiftStdlibToolParams);

    if (swift.isPresent() && swiftStdLibTool.isPresent()) {
      return Optional.of(
          SwiftPlatforms.build(
              platformName,
              sdkPaths.getToolchainPaths(),
              swift.get(),
              swiftStdLibTool.get()));
    } else {
      return Optional.absent();
    }
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
      final String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder,
      final String version,
      final ImmutableList<String> params) {
    return executableFinder.getOptionalToolPath(tool, toolSearchPaths)
        .transform(new Function<Path, Tool>() {
          @Override
          public VersionedTool apply(Path input) {
            return VersionedTool.builder()
                .setPath(input)
                .setName(tool)
                .setVersion(version)
                .setExtraArgs(params)
                .build();
          }
        });
  }

  private static Path getToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {
    Optional<Path> result = executableFinder.getOptionalToolPath(tool, toolSearchPaths);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }
}
