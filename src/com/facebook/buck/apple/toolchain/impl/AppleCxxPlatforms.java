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

package com.facebook.buck.apple.toolchain.impl;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.ArchiverProvider;
import com.facebook.buck.cxx.toolchain.BsdArchiver;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to support building iOS and Mac
 * OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  private static final Logger LOG = Logger.get(AppleCxxPlatforms.class);

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() {}

  private static final String USR_BIN = "usr/bin";

  public static ImmutableList<AppleCxxPlatform> buildAppleCxxPlatforms(
      Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> sdkPaths,
      Optional<ImmutableMap<String, AppleToolchain>> toolchains,
      ProjectFilesystem filesystem,
      BuckConfig buckConfig) {
    if (!sdkPaths.isPresent() || !toolchains.isPresent()) {
      return ImmutableList.of();
    }

    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ImmutableList.Builder<AppleCxxPlatform> appleCxxPlatformsBuilder = ImmutableList.builder();

    XcodeToolFinder xcodeToolFinder = new XcodeToolFinder(appleConfig);
    XcodeBuildVersionCache xcodeBuildVersionCache = new XcodeBuildVersionCache();
    sdkPaths
        .get()
        .forEach(
            (sdk, appleSdkPaths) -> {
              String targetSdkVersion =
                  appleConfig.getTargetSdkVersion(sdk.getApplePlatform()).orElse(sdk.getVersion());
              LOG.debug("SDK %s using default version %s", sdk, targetSdkVersion);
              for (String architecture : sdk.getArchitectures()) {
                appleCxxPlatformsBuilder.add(
                    buildWithXcodeToolFinder(
                        filesystem,
                        sdk,
                        targetSdkVersion,
                        architecture,
                        appleSdkPaths,
                        buckConfig,
                        xcodeToolFinder,
                        xcodeBuildVersionCache));
              }
            });
    return appleCxxPlatformsBuilder.build();
  }

  private static VersionedTool getXcodeTool(
      ProjectFilesystem filesystem,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      AppleConfig appleConfig,
      String toolName,
      String toolVersion) {
    return VersionedTool.of(
        Joiner.on('-').join(ImmutableList.of("apple", toolName)),
        PathSourcePath.of(filesystem, getToolPath(toolName, toolSearchPaths, xcodeToolFinder)),
        appleConfig.getXcodeToolVersion(toolName, toolVersion));
  }

  @VisibleForTesting
  public static AppleCxxPlatform buildWithXcodeToolFinder(
      ProjectFilesystem filesystem,
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      XcodeToolFinder xcodeToolFinder,
      XcodeBuildVersionCache xcodeBuildVersionCache) {
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

    // TODO(beng): Add more and better cflags.
    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    cflagsBuilder.add(targetSdk.getApplePlatform().getMinVersionFlagPrefix() + minVersion);

    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      cflagsBuilder.add("-fembed-bitcode");
    }

    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);

    // Populate Xcode version keys from Xcode's own Info.plist if available.
    Optional<String> xcodeBuildVersion = Optional.empty();
    Optional<Path> developerPath = sdkPaths.getDeveloperPath();
    if (developerPath.isPresent()) {
      Path xcodeBundlePath = developerPath.get().getParent();
      if (xcodeBundlePath != null) {
        Path xcodeInfoPlistPath = xcodeBundlePath.resolve("Info.plist");
        try (InputStream stream = Files.newInputStream(xcodeInfoPlistPath)) {
          NSDictionary parsedXcodeInfoPlist = (NSDictionary) PropertyListParser.parse(stream);

          NSObject xcodeVersionObject = parsedXcodeInfoPlist.objectForKey("DTXcode");
          if (xcodeVersionObject != null) {
            Optional<String> xcodeVersion = Optional.of(xcodeVersionObject.toString());
            platformBuilder.setXcodeVersion(xcodeVersion);
          }
        } catch (IOException e) {
          LOG.warn(
              "Error reading Xcode's info plist %s; ignoring Xcode versions", xcodeInfoPlistPath);
        } catch (PropertyListFormatException
            | ParseException
            | ParserConfigurationException
            | SAXException e) {
          LOG.warn("Error in parsing %s; ignoring Xcode versions", xcodeInfoPlistPath);
        }
      }

      xcodeBuildVersion = xcodeBuildVersionCache.lookup(developerPath.get());
      platformBuilder.setXcodeBuildVersion(xcodeBuildVersion);
      LOG.debug("Xcode build version is: " + xcodeBuildVersion.orElse("<absent>"));
    }

    ImmutableList.Builder<String> versions = ImmutableList.builder();
    versions.add(targetSdk.getVersion());

    ImmutableList<String> toolchainVersions =
        targetSdk.getToolchains().stream()
            .map(AppleToolchain::getVersion)
            .flatMap(RichStream::from)
            .collect(ImmutableList.toImmutableList());
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

    Tool clangPath =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "clang", version);

    Tool clangXxPath =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "clang++", version);

    Tool ar =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ar", version);

    Tool ranlib =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ranlib", version);

    Tool strip =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "strip", version);

    Tool nm =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "nm", version);

    Tool actool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "actool", version);

    Tool ibtool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ibtool", version);

    Tool libtool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "libtool", version);

    Tool momc =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "momc", version);

    Tool xctest =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "xctest", version);

    Tool dsymutil =
        getXcodeTool(
            filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "dsymutil", version);

    // We are seeing a stack overflow in dsymutil during (fat) LTO
    // builds. Upstream dsymutil was patched to avoid recursion in the
    // offending path in https://reviews.llvm.org/D48899, and
    // https://reviews.llvm.org/D45172 mentioned that there is much
    // more stack space available when single threaded.
    if (appleConfig.shouldWorkAroundDsymutilLTOStackOverflowBug()) {
      dsymutil = new CommandTool.Builder(dsymutil).addArg("-num-threads=1").build();
    }

    Tool lipo =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "lipo", version);

    Tool lldb =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "lldb", version);

    Optional<Path> stubBinaryPath =
        targetSdk
            .getApplePlatform()
            .getStubBinaryPath()
            .map(input -> sdkPaths.getSdkPath().resolve(input));

    UserFlavor targetFlavor =
        UserFlavor.of(
            Flavor.replaceInvalidCharacters(targetSdk.getName() + "-" + targetArchitecture),
            String.format("SDK: %s, architecture: %s", targetSdk.getName(), targetArchitecture));
    CxxBuckConfig config =
        appleConfig.useFlavoredCxxSections()
            ? new CxxBuckConfig(buckConfig, targetFlavor)
            : new CxxBuckConfig(buckConfig);

    ImmutableBiMap.Builder<Path, String> sanitizerPaths = ImmutableBiMap.builder();
    sanitizerPaths.put(sdkPaths.getSdkPath(), "APPLE_SDKROOT");
    sanitizerPaths.put(sdkPaths.getPlatformPath(), "APPLE_PLATFORM_DIR");
    if (sdkPaths.getDeveloperPath().isPresent()) {
      sanitizerPaths.put(sdkPaths.getDeveloperPath().get(), "APPLE_DEVELOPER_DIR");
    }

    // https://github.com/facebook/buck/pull/1168: add the root cell's absolute path to the quote
    // include path, and also force it to be sanitized by all user rule keys.
    if (appleConfig.addCellPathToIquotePath()) {
      sanitizerPaths.put(filesystem.getRootPath().getPath(), ".");
      cflagsBuilder.add("-iquote", filesystem.getRootPath().toString());
    }

    DebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(
            DebugPathSanitizer.getPaddedDir(
                ".", config.getDebugPathSanitizerLimit(), File.separatorChar),
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

    Optional<String> buildVersion = Optional.empty();
    Path platformVersionPlistPath = sdkPaths.getPlatformPath().resolve("version.plist");
    try (InputStream versionPlist = Files.newInputStream(platformVersionPlistPath)) {
      NSDictionary versionInfo = (NSDictionary) PropertyListParser.parse(versionPlist);
      if (versionInfo != null) {
        NSObject productBuildVersion = versionInfo.objectForKey("ProductBuildVersion");
        if (productBuildVersion != null) {
          buildVersion = Optional.of(productBuildVersion.toString());
        } else {
          LOG.warn(
              "In %s, missing ProductBuildVersion. Build version will be unset for this platform.",
              platformVersionPlistPath);
        }
      } else {
        LOG.warn(
            "Empty version plist in %s. Build version will be unset for this platform.",
            platformVersionPlistPath);
      }
    } catch (NoSuchFileException e) {
      LOG.warn(
          "%s does not exist. Build version will be unset for this platform.",
          platformVersionPlistPath);
    } catch (PropertyListFormatException
        | SAXException
        | ParserConfigurationException
        | ParseException
        | IOException e) {
      // Some other error occurred, print the exception since it may contain error details.
      LOG.warn(
          e,
          "Failed to parse %s. Build version will be unset for this platform.",
          platformVersionPlistPath);
    }

    PreprocessorProvider aspp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath), CxxToolProvider.Type.CLANG, ToolType.ASPP);
    CompilerProvider as =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG,
            ToolType.AS,
            config.getUseDetailedUntrackedHeaderMessages());
    PreprocessorProvider cpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath), CxxToolProvider.Type.CLANG, ToolType.CPP);
    CompilerProvider cc =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG,
            ToolType.CC,
            config.getUseDetailedUntrackedHeaderMessages());
    PreprocessorProvider cxxpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangXxPath), CxxToolProvider.Type.CLANG, ToolType.CXXPP);
    CompilerProvider cxx =
        new CompilerProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG,
            ToolType.CXX,
            config.getUseDetailedUntrackedHeaderMessages());
    ImmutableList.Builder<String> whitelistBuilder = ImmutableList.builder();
    whitelistBuilder.add("^" + Pattern.quote(sdkPaths.getSdkPath().toString()) + "\\/.*");
    whitelistBuilder.add(
        "^"
            + Pattern.quote(sdkPaths.getPlatformPath() + "/Developer/Library/Frameworks")
            + "\\/.*");
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      LOG.debug("Apple toolchain path: %s", toolchainPath);
      try {
        whitelistBuilder.add("^" + Pattern.quote(toolchainPath.toRealPath().toString()) + "\\/.*");
      } catch (IOException e) {
        LOG.warn(e, "Apple toolchain path could not be resolved: %s", toolchainPath);
      }
    }
    HeaderVerification headerVerification =
        config.getHeaderVerificationOrIgnore().withPlatformWhitelist(whitelistBuilder.build());
    LOG.debug(
        "Headers verification platform whitelist: %s", headerVerification.getPlatformWhitelist());
    ImmutableList<String> ldFlags =
        getLdFlags(targetSdk, filesystem, xcodeToolFinder, toolSearchPaths, appleConfig, version);
    ImmutableList<String> combinedLdFlags =
        ImmutableList.<String>builder().addAll(cflags).addAll(ldFlags).build();
    ImmutableList<Arg> cflagsArgs = ImmutableList.copyOf(StringArg.from(cflags));

    CxxPlatform cxxPlatform =
        CxxPlatforms.build(
            targetFlavor,
            Platform.MACOS,
            config,
            as,
            aspp,
            cc,
            cxx,
            cpp,
            cxxpp,
            new DefaultLinkerProvider(
                LinkerProvider.Type.DARWIN,
                new ConstantToolProvider(clangXxPath),
                config.shouldCacheLinks(),
                appleConfig.shouldLinkScrubConcurrently()),
            StringArg.from(combinedLdFlags),
            ImmutableMultimap.of(),
            strip,
            ArchiverProvider.from(new BsdArchiver(ar)),
            ArchiveContents.NORMAL,
            Optional.of(new ConstantToolProvider(ranlib)),
            new PosixNmSymbolNameTool(new ConstantToolProvider(nm)),
            cflagsArgs,
            ImmutableList.of(),
            cflagsArgs,
            ImmutableList.of(),
            cflagsArgs,
            ImmutableList.of(),
            "dylib",
            "%s.dylib",
            "a",
            "o",
            Optional.empty(),
            compilerDebugPathSanitizer,
            macros,
            Optional.empty(),
            headerVerification,
            config.getPublicHeadersSymlinksEnabled(),
            config.getPrivateHeadersSymlinksEnabled(),
            PicType.PIC);

    ApplePlatform applePlatform = targetSdk.getApplePlatform();
    ImmutableList.Builder<Path> swiftOverrideSearchPathBuilder = ImmutableList.builder();
    AppleSdkPaths.Builder swiftSdkPathsBuilder = AppleSdkPaths.builder().from(sdkPaths);
    Optional<SwiftPlatform> swiftPlatform =
        getSwiftPlatform(
            SwiftTargetTriple.of(
                targetArchitecture,
                "apple",
                applePlatform.getSwiftName().orElse(applePlatform.getName()),
                minVersion),
            version,
            targetSdk,
            swiftSdkPathsBuilder.build(),
            appleConfig.shouldLinkSystemSwift(),
            swiftOverrideSearchPathBuilder.addAll(toolSearchPaths).build(),
            xcodeToolFinder,
            filesystem);

    platformBuilder
        .setCxxPlatform(cxxPlatform)
        .setSwiftPlatform(swiftPlatform)
        .setAppleSdk(targetSdk)
        .setAppleSdkPaths(sdkPaths)
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setLibtool(libtool)
        .setIbtool(ibtool)
        .setMomc(momc)
        .setCopySceneKitAssets(
            getOptionalTool(
                "copySceneKitAssets", toolSearchPaths, xcodeToolFinder, version, filesystem))
        .setXctest(xctest)
        .setDsymutil(dsymutil)
        .setLipo(lipo)
        .setStubBinary(stubBinaryPath)
        .setLldb(lldb)
        .setCodesignAllocate(
            getOptionalTool(
                "codesign_allocate", toolSearchPaths, xcodeToolFinder, version, filesystem))
        .setCodesignProvider(appleConfig.getCodesignProvider());

    return platformBuilder.build();
  }

  private static ImmutableList<String> getLdFlags(
      AppleSdk targetSdk,
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      AppleConfig appleConfig,
      String version) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    if (shouldSetSDKVersion(filesystem, xcodeToolFinder, toolSearchPaths, appleConfig, version)
        .get()) {
      builder.addAll(Linkers.iXlinker("-sdk_version", targetSdk.getVersion()));
    }

    if (appleConfig.linkAllObjC()) {
      builder.addAll(Linkers.iXlinker("-ObjC"));
    }
    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      builder.addAll(Linkers.iXlinker("-bitcode_verify"));
    }

    return builder.build();
  }

  private static Optional<SwiftPlatform> getSwiftPlatform(
      SwiftTargetTriple swiftTarget,
      String version,
      AppleSdk sdk,
      AppleSdkPaths sdkPaths,
      boolean shouldLinkSystemSwift,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      ProjectFilesystem filesystem) {
    ImmutableList<String> swiftParams =
        ImmutableList.of("-frontend", "-sdk", sdkPaths.getSdkPath().toString());

    String platformName = sdk.getApplePlatform().getName();
    ImmutableList.Builder<String> swiftStdlibToolParamsBuilder = ImmutableList.builder();
    swiftStdlibToolParamsBuilder
        .add("--copy")
        .add("--verbose")
        .add("--strip-bitcode")
        .add("--platform")
        .add(platformName);
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      swiftStdlibToolParamsBuilder.add("--toolchain").add(toolchainPath.toString());
      applySourceLibrariesParamIfNeeded(swiftStdlibToolParamsBuilder, toolchainPath, platformName);
    }

    Optional<Tool> swiftc =
        getOptionalToolWithParams(
            "swiftc", toolSearchPaths, xcodeToolFinder, version, swiftParams, filesystem);
    Optional<Tool> swiftStdLibTool =
        getOptionalToolWithParams(
            "swift-stdlib-tool",
            toolSearchPaths,
            xcodeToolFinder,
            version,
            swiftStdlibToolParamsBuilder.build(),
            filesystem);

    return swiftc.map(
        tool ->
            SwiftPlatformFactory.build(
                sdk, sdkPaths, tool, swiftStdLibTool, shouldLinkSystemSwift, swiftTarget));
  }

  private static void applySourceLibrariesParamIfNeeded(
      ImmutableList.Builder<String> swiftStdlibToolParamsBuilder,
      Path toolchainPath,
      String platformName) {
    Optional<Path> foundSwiftRuntimePath =
        SwiftPlatformFactory.findSwiftRuntimePath(toolchainPath, platformName);
    if (foundSwiftRuntimePath.isPresent()) {
      swiftStdlibToolParamsBuilder
          .add("--source-libraries")
          .add(foundSwiftRuntimePath.get().toString());
    } else {
      if (platformName != "driverkit") {
        LOG.info("Swift stdlib missing from: %s for platform: %s", toolchainPath, platformName);
      }
    }
  }

  private static Optional<Tool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      String version,
      ProjectFilesystem filesystem) {
    return getOptionalToolWithParams(
        tool, toolSearchPaths, xcodeToolFinder, version, ImmutableList.of(), filesystem);
  }

  private static Optional<Tool> getOptionalToolWithParams(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      String version,
      ImmutableList<String> params,
      ProjectFilesystem filesystem) {
    return xcodeToolFinder
        .getToolPath(toolSearchPaths, tool)
        .map(
            input -> VersionedTool.of(tool, PathSourcePath.of(filesystem, input), version, params));
  }

  private static Path getToolPath(
      String tool, ImmutableList<Path> toolSearchPaths, XcodeToolFinder xcodeToolFinder) {
    Optional<Path> result = xcodeToolFinder.getToolPath(toolSearchPaths, tool);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

  private static Supplier<Boolean> shouldSetSDKVersion(
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      AppleConfig appleConfig,
      String version) {
    return MoreSuppliers.memoize(
        () -> {
          // If the Clang driver detects ld version at 520 or above, it will pass -platform_version,
          // otherwise it will pass -<platform>_version_min. As -platform_version is incompatible
          // with -sdk_version (which Buck passes), we should only be passing -sdk_version if we
          // believe the driver will not pass it.
          // https://reviews.llvm.org/rG25ce33a6e4f3b13732c0f851e68390dc2acb9123
          Optional<Double> ldVersion =
              getLdVersion(filesystem, xcodeToolFinder, toolSearchPaths, appleConfig, version);
          if (ldVersion.isPresent()) {
            return ldVersion.get() < 520;
          }
          return true;
        });
  }

  private static Optional<Double> getLdVersion(
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      AppleConfig appleConfig,
      String version) {

    VersionedTool ld =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ld", version);

    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(ld.getPath().toString(), "-v"))
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result;
    try {
      result =
          executor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.empty(),
              /* timeOutHandler */ Optional.empty());
    } catch (InterruptedException | IOException e) {
      LOG.debug("Could not execute ld -v, continuing with setting the sdk_version.");
      return Optional.empty();
    }

    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          result.getMessageForUnexpectedResult(ld.getPath().toString() + " -v"));
    }

    Double parsedVersion = 0.0;
    try {
      // We expect the version string to be of the form "@(#)PROGRAM:ld  PROJECT:ld64-556.6"
      String versionStr = result.getStderr().get().split("\n")[0];
      parsedVersion = Double.parseDouble(versionStr.split("ld64-")[1]);
    } catch (Exception e) {
      LOG.debug(
          "Unable to parse the ld version from %s, continuing with setting the sdk_version.",
          result.getStderr().get());
    }

    return Optional.of(parsedVersion);
  }

  @VisibleForTesting
  public static class XcodeBuildVersionCache {
    private final Map<Path, Optional<String>> cache = new HashMap<>();

    /**
     * Returns the Xcode build version. This is an alphanumeric string as output by {@code
     * xcodebuild -version} and shown in the About Xcode window. This value is embedded into the
     * plist of app bundles built by Xcode, under the field named {@code DTXcodeBuild}
     *
     * @param developerDir Path to developer dir, i.e. /Applications/Xcode.app/Contents/Developer
     * @return the xcode build version if found, nothing if it fails to be found, or the version
     *     plist file cannot be read.
     */
    protected Optional<String> lookup(Path developerDir) {
      return cache.computeIfAbsent(
          developerDir,
          ignored -> {
            Path versionPlist = developerDir.getParent().resolve("version.plist");
            NSString result;
            try {
              NSDictionary dict =
                  (NSDictionary) PropertyListParser.parse(Files.readAllBytes(versionPlist));
              result = (NSString) dict.get("ProductBuildVersion");
            } catch (IOException
                | ClassCastException
                | SAXException
                | PropertyListFormatException
                | ParseException e) {
              LOG.warn(
                  e,
                  "%s: Cannot find xcode build version, file is in an invalid format.",
                  versionPlist);
              return Optional.empty();
            } catch (ParserConfigurationException e) {
              throw new IllegalStateException("plist parser threw unexpected exception", e);
            }
            if (result != null) {
              return Optional.of(result.toString());
            } else {
              LOG.warn(
                  "%s: Cannot find xcode build version, file is in an invalid format.",
                  versionPlist);
              return Optional.empty();
            }
          });
    }
  }
}
