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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This {@link BuildRule} creates {@link AppleCxxPlatform} using {@link ApplePlatformBuildRule}.
 * It's a {@link NoopBuildRule} with no build steps or outputs.
 */
public class AppleToolchainSetBuildRule extends NoopBuildRule {

  private final Map<String, ApplePlatformBuildRule> applePlatformMapping;
  private final Optional<Path> developerPath;
  private final String xcodeVersion;
  private final String xcodeBuildVersion;

  private final Map<Flavor, AppleCxxPlatform> resolvedCache;

  public AppleToolchainSetBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedMap<String, ApplePlatformBuildRule> applePlatformMapping,
      Optional<Path> developerPath,
      String xcodeVersion,
      String xcodeBuildVersion) {
    super(buildTarget, projectFilesystem);

    this.applePlatformMapping = applePlatformMapping;
    this.developerPath = developerPath;
    this.xcodeVersion = xcodeVersion;
    this.xcodeBuildVersion = xcodeBuildVersion;
    this.resolvedCache = new ConcurrentHashMap<>();
  }

  public AppleCxxPlatform getAppleCxxPlatform(Flavor flavor) {
    return resolvedCache.computeIfAbsent(flavor, this::computePlatform);
  }

  private AppleCxxPlatform computePlatform(Flavor flavor) {
    if (!applePlatformMapping.containsKey(flavor.getName())) {
      throw new HumanReadableException(
          "Apple platform '%s' is not defined in %s", flavor, getBuildTarget());
    }
    ApplePlatformBuildRule platformRule = applePlatformMapping.get(flavor.getName());

    CxxPlatform.Builder cxxPlatformBuilder =
        CxxPlatform.builder().from(platformRule.getCxxPlatform(flavor));

    ImmutableBiMap.Builder<Path, String> sanitizerPathsBuilder =
        platformRule.getSanitizerPathsBuilder();
    developerPath.ifPresent(path -> sanitizerPathsBuilder.put(path, "APPLE_DEVELOPER_DIR"));
    DebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(
            DebugPathSanitizer.getPaddedDir(".", 250, File.separatorChar),
            sanitizerPathsBuilder.build());
    cxxPlatformBuilder.setCompilerDebugPathSanitizer(compilerDebugPathSanitizer);

    ImmutableMap.Builder<String, String> macrosBuilder = platformRule.getMacrosBuilder();
    macrosBuilder.put(
        "CURRENT_ARCH",
        ApplePlatform.findArchitecture(flavor).orElseThrow(IllegalStateException::new));
    developerPath.ifPresent(path -> macrosBuilder.put("DEVELOPER_DIR", path.toString()));
    cxxPlatformBuilder.setFlagMacros(macrosBuilder.build());

    return platformRule
        .getAppleCxxPlatformBuilder()
        .setCxxPlatform(cxxPlatformBuilder.build())
        .setSwiftPlatform(platformRule.getSwiftPlatform(flavor))
        .setXcodeVersion(xcodeVersion)
        .setXcodeBuildVersion(xcodeBuildVersion)
        .setAppleSdkPaths(
            platformRule.getAppleSdkPathsBuilder().setDeveloperPath(developerPath).build())
        .setAppleSdk(platformRule.getAppleSdk(flavor))
        .setStubBinary(platformRule.getStubBinaryPath(flavor))
        .build();
  }
}
