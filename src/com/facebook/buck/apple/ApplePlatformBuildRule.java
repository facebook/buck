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
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.swift.SwiftToolchainBuildRule;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * This {@link BuildRule} holds {@link ApplePlatformDescriptionArg} to create {@link
 * AppleCxxPlatform} in {@link AppleToolchainBuildRule}. It's a {@link NoopBuildRule} with no build
 * steps or outputs.
 */
public class ApplePlatformBuildRule extends NoopBuildRule {

  private final Path sdkPath;
  private final Path platformPath;
  private final String sdkName;
  private final String version;
  private final Optional<String> buildVersion;
  private final String minVersion;
  private final Tool actool;
  private final Tool dsymutil;
  private final Tool ibtool;
  private final Tool libtool;
  private final Tool lipo;
  private final Tool lldb;
  private final Tool momc;
  private final Tool xctest;
  private final Optional<Tool> copySceneKitAssets;
  private final ToolProvider codesign;
  private final Tool codesignAllocate;
  private final ProvidesCxxPlatform cxxToolchainRule;
  private final Optional<SwiftToolchainBuildRule> swiftToolchainRule;

  public ApplePlatformBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Path platformPath,
      Path sdkPath,
      String sdkName,
      String version,
      Optional<String> buildVersion,
      String minVersion,
      Tool actool,
      Tool dsymutil,
      Tool ibtool,
      Tool libtool,
      Tool lipo,
      Tool lldb,
      Tool momc,
      Tool xctest,
      Optional<Tool> copySceneKitAssets,
      ToolProvider codesign,
      Tool codesignAllocate,
      ProvidesCxxPlatform cxxToolchainRule,
      Optional<SwiftToolchainBuildRule> swiftToolchainRule) {
    super(buildTarget, projectFilesystem);

    this.platformPath = platformPath;
    this.sdkPath = sdkPath;
    this.sdkName = sdkName;
    this.version = version;
    this.buildVersion = buildVersion;
    this.minVersion = minVersion;
    this.actool = actool;
    this.dsymutil = dsymutil;
    this.ibtool = ibtool;
    this.libtool = libtool;
    this.lipo = lipo;
    this.lldb = lldb;
    this.momc = momc;
    this.xctest = xctest;
    this.copySceneKitAssets = copySceneKitAssets;
    this.codesign = codesign;
    this.codesignAllocate = codesignAllocate;
    this.cxxToolchainRule = cxxToolchainRule;
    this.swiftToolchainRule = swiftToolchainRule;
  }

  public AppleCxxPlatform.Builder getAppleCxxPlatformBuilder() {
    return AppleCxxPlatform.builder()
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setLibtool(libtool)
        .setIbtool(ibtool)
        .setMomc(momc)
        .setCopySceneKitAssets(copySceneKitAssets)
        .setXctest(xctest)
        .setDsymutil(dsymutil)
        .setLipo(lipo)
        .setLldb(lldb)
        .setCodesignProvider(codesign)
        .setCodesignAllocate(codesignAllocate);
  }

  public CxxPlatform getCxxPlatform(Flavor flavor) {
    return cxxToolchainRule.getPlatformWithFlavor(flavor);
  }

  /** Provides SwiftPlatform for given platform flavor if defined */
  public Optional<SwiftPlatform> getSwiftPlatform(Flavor flavor) {
    ApplePlatform applePlatform = ApplePlatform.fromFlavor(flavor);
    SwiftTargetTriple swiftTarget =
        SwiftTargetTriple.of(
            ApplePlatform.findArchitecture(flavor).orElseThrow(IllegalArgumentException::new),
            "apple",
            applePlatform.getSwiftName().orElse(applePlatform.getName()),
            minVersion);
    return swiftToolchainRule.map(rule -> rule.getSwiftPlatform(swiftTarget));
  }

  public AppleSdkPaths.Builder getAppleSdkPathsBuilder() {
    return AppleSdkPaths.builder()
        .setSdkPath(sdkPath)
        .setPlatformPath(platformPath)
        .setToolchainPaths(ImmutableList.of());
  }

  /** Provides AppleSdk for given platform flavor */
  public AppleSdk getAppleSdk(Flavor flavor) {
    ApplePlatform applePlatform = ApplePlatform.fromFlavor(flavor);
    return AppleSdk.builder()
        .setName(sdkName)
        .setVersion(version)
        .setToolchains(ImmutableList.of())
        .setApplePlatform(applePlatform)
        .setArchitectures(applePlatform.getArchitectures())
        .build();
  }

  /** Stub binary path for given platform flavor */
  public Optional<Path> getStubBinaryPath(Flavor flavor) {
    return ApplePlatform.fromFlavor(flavor).getStubBinaryPath().map(sdkPath::resolve);
  }

  /** Path sanitizer builder */
  public ImmutableBiMap.Builder<Path, String> getSanitizerPathsBuilder() {
    ImmutableBiMap.Builder<Path, String> sanitizerPathsBuilder = ImmutableBiMap.builder();
    sanitizerPathsBuilder.put(sdkPath, "APPLE_SDKROOT");
    sanitizerPathsBuilder.put(platformPath, "APPLE_PLATFORM_DIR");
    return sanitizerPathsBuilder;
  }

  /** Flag macros builder */
  public ImmutableMap.Builder<String, String> getMacrosBuilder() {
    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkPath.toString());
    macrosBuilder.put("PLATFORM_DIR", platformPath.toString());
    return macrosBuilder;
  }
}
