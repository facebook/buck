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
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.core.toolchain.toolprovider.impl.ToolProviders;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
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

  private final ProvidesCxxPlatform cxxPlatformRule;
  private final Path sdkPath;
  private final Path platformPath;
  private final ActionGraphBuilder actionGraphBuilder;
  private final ApplePlatformDescriptionArg descriptionArgs;

  public ApplePlatformBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder actionGraphBuilder,
      ApplePlatformDescriptionArg descriptionArgs,
      ProvidesCxxPlatform cxxPlatformRule) {
    super(buildTarget, projectFilesystem);

    this.actionGraphBuilder = actionGraphBuilder;
    this.descriptionArgs = descriptionArgs;
    this.cxxPlatformRule = cxxPlatformRule;

    SourcePathResolverAdapter pathResolver = actionGraphBuilder.getSourcePathResolver();
    this.sdkPath = pathResolver.getAbsolutePath(descriptionArgs.getSdkPath());
    this.platformPath = pathResolver.getAbsolutePath(descriptionArgs.getPlatformPath());
  }

  public AppleCxxPlatform.Builder getAppleCxxPlatformBuilder() {
    // We are seeing a stack overflow in dsymutil during (fat) LTO
    // builds. Upstream dsymutil was patched to avoid recursion in the
    // offending path in https://reviews.llvm.org/D48899, and
    // https://reviews.llvm.org/D45172 mentioned that there is much
    // more stack space available when single threaded.
    Tool dsymutil = Tools.resolveTool(descriptionArgs.getDsymutil(), actionGraphBuilder);
    if (descriptionArgs.getWorkAroundDsymutilLtoStackOverflowBug().orElse(false)) {
      dsymutil = new CommandTool.Builder(dsymutil).addArg("-num-threads=1").build();
    }
    return AppleCxxPlatform.builder()
        .setMinVersion(descriptionArgs.getMinVersion())
        .setBuildVersion(descriptionArgs.getBuildVersion())
        .setActool(Tools.resolveTool(descriptionArgs.getActool(), actionGraphBuilder))
        .setLibtool(Tools.resolveTool(descriptionArgs.getLibtool(), actionGraphBuilder))
        .setIbtool(Tools.resolveTool(descriptionArgs.getIbtool(), actionGraphBuilder))
        .setMomc(Tools.resolveTool(descriptionArgs.getMomc(), actionGraphBuilder))
        .setCopySceneKitAssets(
            descriptionArgs
                .getCopySceneKitAssets()
                .map(path -> Tools.resolveTool(path, actionGraphBuilder)))
        .setXctest(Tools.resolveTool(descriptionArgs.getXctest(), actionGraphBuilder))
        .setDsymutil(dsymutil)
        .setLipo(Tools.resolveTool(descriptionArgs.getLipo(), actionGraphBuilder))
        .setLldb(Tools.resolveTool(descriptionArgs.getLldb(), actionGraphBuilder))
        .setCodesignProvider(ToolProviders.getToolProvider(descriptionArgs.getCodesign()))
        .setCodesignAllocate(
            Tools.resolveTool(descriptionArgs.getCodesignAllocate(), actionGraphBuilder));
  }

  public CxxPlatform getCxxPlatform(Flavor flavor) {
    return cxxPlatformRule.getPlatformWithFlavor(flavor);
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
        .setName(descriptionArgs.getSdkName())
        .setVersion(descriptionArgs.getVersion())
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
