/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;

public class SwiftPlatformFactory {

  // Utility class, do not instantiate.
  private SwiftPlatformFactory() {}

  public static SwiftPlatform build(
      String platformName, Set<Path> toolchainPaths, Tool swiftc, Optional<Tool> swiftStdLibTool) {
    SwiftPlatform.Builder builder =
        SwiftPlatform.builder()
            .setSwiftc(swiftc)
            .setSwiftStdlibTool(swiftStdLibTool)
            .setSwiftSharedLibraryRunPaths(buildSharedRunPaths(platformName));

    for (Path toolchainPath : toolchainPaths) {
      Path swiftRuntimePath = toolchainPath.resolve("usr/lib/swift").resolve(platformName);
      if (Files.isDirectory(swiftRuntimePath)) {
        builder.addSwiftRuntimePaths(swiftRuntimePath);
      }
      Path swiftStaticRuntimePath =
          toolchainPath.resolve("usr/lib/swift_static").resolve(platformName);
      if (Files.isDirectory(swiftStaticRuntimePath)) {
        builder.addSwiftStaticRuntimePaths(swiftStaticRuntimePath);
      }
    }
    return builder.build();
  }

  private static ImmutableList<Path> buildSharedRunPaths(String platformName) {
    ApplePlatformType platformType = ApplePlatformType.of(platformName);
    if (platformType == ApplePlatformType.MAC) {
      return ImmutableList.of(
          Paths.get("@executable_path", "..", "Frameworks"),
          Paths.get("@loader_path", "..", "Frameworks"));
    }

    return ImmutableList.of(
        Paths.get("@executable_path", "Frameworks"), Paths.get("@loader_path", "Frameworks"));
  }
}
