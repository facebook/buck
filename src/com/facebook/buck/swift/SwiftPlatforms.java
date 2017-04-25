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

package com.facebook.buck.swift;

import com.facebook.buck.rules.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class SwiftPlatforms {

  // Utility class, do not instantiate.
  private SwiftPlatforms() {}

  public static SwiftPlatform build(
      String platformName, Set<Path> toolchainPaths, Tool swiftc, Tool swiftStdLibTool) {
    SwiftPlatform.Builder builder =
        SwiftPlatform.builder().setSwiftc(swiftc).setSwiftStdlibTool(swiftStdLibTool);

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
}
