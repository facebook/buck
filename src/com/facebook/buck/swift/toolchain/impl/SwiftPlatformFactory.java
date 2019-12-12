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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

public class SwiftPlatformFactory {

  private static final Logger LOG = Logger.get(SwiftPlatformFactory.class);

  // Utility class, do not instantiate.
  private SwiftPlatformFactory() {}

  public static SwiftPlatform build(
      AppleSdk sdk,
      AppleSdkPaths sdkPaths,
      Tool swiftc,
      Optional<Tool> swiftStdLibTool,
      boolean shouldLinkSystemSwift,
      SwiftTargetTriple swiftTarget) {
    String platformName = sdk.getApplePlatform().getName();
    Set<Path> toolchainPaths = sdkPaths.getToolchainPaths();
    SwiftPlatform.Builder builder =
        SwiftPlatform.builder()
            .setSwiftc(swiftc)
            .setSwiftStdlibTool(swiftStdLibTool)
            .setSwiftSharedLibraryRunPaths(buildSharedRunPaths(platformName, shouldLinkSystemSwift))
            .setSwiftTarget(swiftTarget);

    for (Path toolchainPath : toolchainPaths) {
      Optional<Path> swiftRuntimePathForBundling =
          findSwiftRuntimePath(toolchainPath, platformName);
      if (swiftRuntimePathForBundling.isPresent()) {
        builder.addSwiftRuntimePathsForBundling(swiftRuntimePathForBundling.get());
      }

      Optional<Path> foundSwiftCompatibilityRuntimePath =
          findSwiftCompatibilityRuntimePath(toolchainPath, platformName);
      if (foundSwiftCompatibilityRuntimePath.isPresent()) {
        builder.addSwiftRuntimePathsForBundling(foundSwiftCompatibilityRuntimePath.get());
      }

      Path swiftStaticRuntimePath =
          toolchainPath.resolve("usr/lib/swift_static").resolve(platformName);
      if (Files.isDirectory(swiftStaticRuntimePath)) {
        builder.addSwiftStaticRuntimePaths(swiftStaticRuntimePath);
      }
    }

    ImmutableList<Path> linkPaths =
        SwiftSdkLayoutType.getLinkPaths(sdk, sdkPaths, sdk.getApplePlatform().getPlatformName());
    for (Path linkPath : linkPaths) {
      if (Files.isDirectory(linkPath)) {
        LOG.debug("Found swift link path at %s", linkPath);
        builder.addSwiftRuntimePathsForLinking(linkPath);
      }
    }
    return builder.build();
  }

  public static Optional<Path> findSwiftRuntimePath(Path toolchainPath, String platformName) {
    if (platformName == "driverkit") {
      return Optional.empty();
    }

    // The location of the Swift stdlib changed in Xcode 11, and swift-stdlib-tool wasn't updated to
    // accommodate, so we need to manually find and supply the new path.
    Path swiftRuntimePath = toolchainPath.resolve("usr/lib/swift").resolve(platformName);
    String libSwiftCoreDylibName = "libswiftCore.dylib";
    LOG.debug("Searching for swift toolchain in: %s", toolchainPath.toString());
    if (Files.exists(swiftRuntimePath.resolve(libSwiftCoreDylibName))) {
      LOG.debug("Found swift toolchain at %s", swiftRuntimePath.toString());
      return Optional.of(swiftRuntimePath);
    } else {
      Path toolchainUsrLib = toolchainPath.resolve("usr/lib");
      try (DirectoryStream<Path> toolchainUsrLibDirectoryStream =
          Files.newDirectoryStream(toolchainUsrLib, "swift*")) {
        // Go through all swift* folders inside toolchain/usr/lib and find the first that contains
        // libSwiftCore.dylib.
        Path swiftStdLibRoot = null;
        for (Path swiftFolder : toolchainUsrLibDirectoryStream) {
          if (Files.exists(swiftFolder.resolve(platformName).resolve(libSwiftCoreDylibName))) {
            swiftStdLibRoot = swiftFolder.resolve(platformName);
          }

          if (swiftStdLibRoot != null) {
            break;
          }
        }
        if (swiftStdLibRoot != null) {
          return Optional.of(swiftStdLibRoot);
        }
      } catch (IOException x) {
        LOG.debug(
            "Unable to find swift libraries in toolchain: %s for platform: %s. Exception: %s.",
            toolchainPath, platformName, x.getLocalizedMessage());
        return Optional.empty();
      }
      LOG.debug(
          "Unable to find swift libraries in toolchain: %s for platform: %s",
          toolchainPath, platformName);
      return Optional.empty();
    }
  }

  public static Optional<Path> findSwiftCompatibilityRuntimePath(
      Path toolchainPath, String platformName) {
    if (platformName == "driverkit") {
      return Optional.empty();
    }

    // Currently Xcode includes swift and swift-5.0 directories, and each contains different
    // contents. Specifically, swift/platform contains the libswiftCompatibility50 and
    // libswiftCompatibilityDynamicReplacements libraries which are *also* necessary.
    Path swiftRuntimePath = toolchainPath.resolve("usr/lib/swift").resolve(platformName);
    String libSwiftCompatibilityLibraryName = "libswiftCompatibility50.a";
    LOG.debug("Searching for swift compatibility toolchain in %s", toolchainPath.toString());
    if (Files.exists(swiftRuntimePath.resolve(libSwiftCompatibilityLibraryName))) {
      LOG.debug("Found swift compatibility toolchain at %s", toolchainPath.toString());
      return Optional.of(swiftRuntimePath);
    }

    return Optional.empty();
  }

  private static ImmutableList<Path> buildSharedRunPaths(
      String platformName, boolean shouldLinkSystemSwift) {
    ApplePlatformType platformType = ApplePlatformType.of(platformName);

    ArrayList<Path> paths = new ArrayList<Path>();
    if (shouldLinkSystemSwift) {
      paths.add(Paths.get("/usr/lib/swift"));
    }
    if (platformType == ApplePlatformType.MAC) {
      paths.add(Paths.get("@executable_path", "..", "Frameworks"));
      paths.add(Paths.get("@loader_path", "..", "Frameworks"));
    } else {
      paths.add(Paths.get("@executable_path", "Frameworks"));
      paths.add(Paths.get("@loader_path", "Frameworks"));
    }

    return ImmutableList.copyOf(paths);
  }
}
