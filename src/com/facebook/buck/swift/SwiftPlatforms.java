package com.facebook.buck.swift;

import com.facebook.buck.rules.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class SwiftPlatforms {

  // Utility class, do not instantiate.
  private SwiftPlatforms() { }

  public static SwiftPlatform build(
      String platformName,
      Set<Path> toolchainPaths,
      Tool swift,
      Tool swiftStdLibTool) {
    SwiftPlatform.Builder builder = SwiftPlatform.builder()
        .setSwift(swift)
        .setSwiftStdlibTool(swiftStdLibTool);

    for (Path toolchainPath : toolchainPaths) {
      Path swiftRuntimePath = toolchainPath
          .resolve("usr/lib/swift")
          .resolve(platformName);
      if (Files.isDirectory(swiftRuntimePath)) {
        builder.addSwiftRuntimePaths(swiftRuntimePath);
      }
      Path swiftStaticRuntimePath = toolchainPath
          .resolve("usr/lib/swift_static")
          .resolve(platformName);
      if (Files.isDirectory(swiftStaticRuntimePath)) {
        builder.addSwiftStaticRuntimePaths(swiftStaticRuntimePath);
      }
    }
    return builder.build();
  }
}
