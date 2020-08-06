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
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.core.toolchain.toolprovider.impl.ToolProviders;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.SwiftToolchainBuildRule;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** Defines an apple_toolchain rule which provides {@link AppleCxxPlatform}. */
public class AppleToolchainDescription
    implements DescriptionWithTargetGraph<AppleToolchainDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleToolchainDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());
    UserFlavor targetFlavor =
        UserFlavor.of(
            Flavor.replaceInvalidCharacters(args.getSdkName() + "-" + args.getArchitecture()),
            String.format("SDK: %s, architecture: %s", args.getSdkName(), args.getArchitecture()));
    if (!ApplePlatform.isPlatformFlavor(targetFlavor)) {
      throw new HumanReadableException(
          "Can't find Apple platform for SDK: %s and architecture: %s",
          args.getSdkName(), args.getArchitecture());
    }
    ApplePlatform applePlatform = ApplePlatform.fromFlavor(targetFlavor);

    ActionGraphBuilder actionGraphBuilder = context.getActionGraphBuilder();
    BuildRule cxxToolchainRule = actionGraphBuilder.getRule(args.getCxxToolchain());
    if (!(cxxToolchainRule instanceof ProvidesCxxPlatform)) {
      throw new HumanReadableException(
          "Expected %s to be an instance of cxx_toolchain.", cxxToolchainRule.getBuildTarget());
    }
    Optional<BuildRule> swiftToolchainRule =
        args.getSwiftToolchain().map(actionGraphBuilder::getRule);
    if (swiftToolchainRule.isPresent()
        && !(swiftToolchainRule.get() instanceof SwiftToolchainBuildRule)) {
      throw new HumanReadableException(
          "Expected %s to be an instance of swift_toolchain.",
          swiftToolchainRule.get().getBuildTarget());
    }

    SourcePathResolverAdapter pathResolver = actionGraphBuilder.getSourcePathResolver();

    // We are seeing a stack overflow in dsymutil during (fat) LTO
    // builds. Upstream dsymutil was patched to avoid recursion in the
    // offending path in https://reviews.llvm.org/D48899, and
    // https://reviews.llvm.org/D45172 mentioned that there is much
    // more stack space available when single threaded.
    Tool dsymutil = Tools.resolveTool(args.getDsymutil(), actionGraphBuilder);
    if (args.getWorkAroundDsymutilLtoStackOverflowBug().orElse(false)) {
      dsymutil = new CommandTool.Builder(dsymutil).addArg("-num-threads=1").build();
    }

    Path sdkPath = pathResolver.getAbsolutePath(args.getSdkPath());
    Path platformPath = pathResolver.getAbsolutePath(args.getPlatformPath());
    Optional<Path> developerPath = args.getDeveloperPath().map(pathResolver::getAbsolutePath);
    AppleSdkPaths sdkPaths =
        AppleSdkPaths.builder()
            .setSdkPath(sdkPath)
            .setPlatformPath(platformPath)
            .setToolchainPaths(ImmutableList.of())
            .setDeveloperPath(developerPath)
            .build();

    AppleSdk sdk =
        AppleSdk.builder()
            .setName(args.getSdkName())
            .setVersion(args.getVersion())
            .setToolchains(ImmutableList.of())
            .setApplePlatform(applePlatform)
            .setArchitectures(applePlatform.getArchitectures())
            .build();

    SwiftTargetTriple swiftTarget =
        SwiftTargetTriple.of(
            args.getArchitecture(),
            "apple",
            applePlatform.getSwiftName().orElse(applePlatform.getName()),
            args.getMinVersion());
    Optional<SwiftPlatform> swiftPlatform =
        swiftToolchainRule
            .map(SwiftToolchainBuildRule.class::cast)
            .map(rule -> rule.getSwiftPlatform(swiftTarget));

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatform.builder()
            .setMinVersion(args.getMinVersion())
            .setBuildVersion(args.getBuildVersion())
            .setActool(Tools.resolveTool(args.getActool(), actionGraphBuilder))
            .setLibtool(Tools.resolveTool(args.getLibtool(), actionGraphBuilder))
            .setIbtool(Tools.resolveTool(args.getIbtool(), actionGraphBuilder))
            .setMomc(Tools.resolveTool(args.getMomc(), actionGraphBuilder))
            .setCopySceneKitAssets(
                args.getCopySceneKitAssets()
                    .map(path -> Tools.resolveTool(path, actionGraphBuilder)))
            .setXctest(Tools.resolveTool(args.getXctest(), actionGraphBuilder))
            .setDsymutil(dsymutil)
            .setLipo(Tools.resolveTool(args.getLipo(), actionGraphBuilder))
            .setLldb(Tools.resolveTool(args.getLldb(), actionGraphBuilder))
            .setCodesignProvider(ToolProviders.getToolProvider(args.getCodesign()))
            .setCodesignAllocate(Tools.resolveTool(args.getCodesignAllocate(), actionGraphBuilder))
            .setCxxPlatform(
                getCxxPlatform(
                    (ProvidesCxxPlatform) cxxToolchainRule,
                    pathResolver,
                    targetFlavor,
                    args.getSdkPath(),
                    args.getPlatformPath(),
                    args.getDeveloperPath()))
            .setSwiftPlatform(swiftPlatform)
            .setXcodeVersion(args.getXcodeVersion())
            .setXcodeBuildVersion(args.getXcodeBuildVersion())
            .setAppleSdkPaths(sdkPaths)
            .setAppleSdk(sdk)
            .setStubBinary(applePlatform.getStubBinaryPath().map(sdkPath::resolve))
            .build();

    return new AppleToolchainBuildRule(
        buildTarget, context.getProjectFilesystem(), appleCxxPlatform);
  }

  @Override
  public Class<AppleToolchainDescriptionArg> getConstructorArgType() {
    return AppleToolchainDescriptionArg.class;
  }

  private CxxPlatform getCxxPlatform(
      ProvidesCxxPlatform cxxToolchainRule,
      SourcePathResolverAdapter pathResolver,
      Flavor flavor,
      SourcePath sdkRoot,
      SourcePath platformRoot,
      Optional<SourcePath> developerRoot) {
    CxxPlatform currentCxxPlatform = cxxToolchainRule.getPlatformWithFlavor(flavor);
    CxxPlatform.Builder cxxPlatformBuilder = CxxPlatform.builder().from(currentCxxPlatform);

    Path sdkRootPath = pathResolver.getAbsolutePath(sdkRoot);
    Path platformRootPath = pathResolver.getAbsolutePath(platformRoot);
    Optional<Path> developerRootPath = developerRoot.map(pathResolver::getAbsolutePath);

    ImmutableBiMap.Builder<Path, String> sanitizerPathsBuilder = ImmutableBiMap.builder();
    sanitizerPathsBuilder.put(sdkRootPath, "APPLE_SDKROOT");
    sanitizerPathsBuilder.put(platformRootPath, "APPLE_PLATFORM_DIR");
    developerRootPath.ifPresent(path -> sanitizerPathsBuilder.put(path, "APPLE_DEVELOPER_DIR"));
    DebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(
            DebugPathSanitizer.getPaddedDir(".", 250, File.separatorChar),
            sanitizerPathsBuilder.build());
    cxxPlatformBuilder.setCompilerDebugPathSanitizer(compilerDebugPathSanitizer);

    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, Arg> macrosArgsBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkRootPath.toString());
    macrosArgsBuilder.put("SDKROOT", SourcePathArg.of(sdkRoot));
    macrosBuilder.put("PLATFORM_DIR", platformRootPath.toString());
    macrosArgsBuilder.put("PLATFORM_DIR", SourcePathArg.of(platformRoot));
    macrosBuilder.put(
        "CURRENT_ARCH",
        ApplePlatform.findArchitecture(flavor).orElseThrow(IllegalStateException::new));
    macrosArgsBuilder.put(
        "CURRENT_ARCH",
        StringArg.of(
            ApplePlatform.findArchitecture(flavor).orElseThrow(IllegalStateException::new)));
    developerRootPath.ifPresent(path -> macrosBuilder.put("DEVELOPER_DIR", path.toString()));
    developerRoot.ifPresent(path -> macrosArgsBuilder.put("DEVELOPER_DIR", SourcePathArg.of(path)));
    cxxPlatformBuilder.setFlagMacros(macrosBuilder.build());

    // Expand macros in cxx platform flags.
    CxxFlags.translateCxxPlatformFlags(
        cxxPlatformBuilder, currentCxxPlatform, macrosArgsBuilder.build());

    return cxxPlatformBuilder.build();
  }

  /**
   * apple_toolchain defines tools, cxx and swift toolchains and other properties to define
   * AppleCxxPlatform.
   */
  @RuleArg
  interface AbstractAppleToolchainDescriptionArg extends BuildRuleArg {
    /** Name of SDK which should be used. */
    String getSdkName();

    /** Target architecture. */
    String getArchitecture();

    /** Path to Apple platform */
    SourcePath getPlatformPath();

    /** Path to Apple SDK. */
    SourcePath getSdkPath();

    /** Version of SDK. */
    String getVersion();

    /** Build version. Can be found in ProductBuildVersion in platform version.plist */
    Optional<String> getBuildVersion();

    /** Target SDK version. */
    String getMinVersion();

    /** actool binary. */
    SourcePath getActool();

    /** dsymutil binary. */
    SourcePath getDsymutil();

    /** ibtool binary. */
    SourcePath getIbtool();

    /** libtool binary. */
    SourcePath getLibtool();

    /** lipo binary. */
    SourcePath getLipo();

    /** lldb binary. */
    SourcePath getLldb();

    /** momc binary. */
    SourcePath getMomc();

    /** xctest binary. */
    SourcePath getXctest();

    /** copySceneKitAssets binary. */
    Optional<SourcePath> getCopySceneKitAssets();

    /** codesign binary. */
    SourcePath getCodesign();

    /** codesign_allocate binary. */
    SourcePath getCodesignAllocate();

    /** Target for the cxx toolchain which should be used for this SDK. */
    BuildTarget getCxxToolchain();

    /** Target for the swift toolchain which should be used for this SDK. */
    Optional<BuildTarget> getSwiftToolchain();

    /** Developer directory of the toolchain */
    Optional<SourcePath> getDeveloperPath();

    /** XCode version which can be found in DTXcode in XCode plist */
    String getXcodeVersion();

    /** XCode build version from from 'xcodebuild -version' */
    String getXcodeBuildVersion();

    /** If work around for dsymutil should be used. */
    Optional<Boolean> getWorkAroundDsymutilLtoStackOverflowBug();
  }
}
