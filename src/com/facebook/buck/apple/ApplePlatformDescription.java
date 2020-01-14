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
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.facebook.buck.swift.SwiftToolchainBuildRule;
import com.google.common.base.Verify;
import java.util.Optional;
import org.immutables.value.Value;

/** Defines an apple_platform rule which provides values to fill {@link AppleCxxPlatform}. */
public class ApplePlatformDescription
    implements DescriptionWithTargetGraph<ApplePlatformDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ApplePlatformDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());
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
    return new ApplePlatformBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        actionGraphBuilder,
        args,
        (ProvidesCxxPlatform) cxxToolchainRule,
        swiftToolchainRule.map(SwiftToolchainBuildRule.class::cast));
  }

  @Override
  public Class<ApplePlatformDescriptionArg> getConstructorArgType() {
    return ApplePlatformDescriptionArg.class;
  }

  /**
   * apple_platform defines tools, cxx and swift toolchains and some properties of Apple Platform.
   */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractApplePlatformDescriptionArg extends BuildRuleArg {
    /** Path to Apple platform */
    SourcePath getPlatformPath();

    /** Path to Apple SDK. */
    SourcePath getSdkPath();

    /** Name of SDK which should be used. */
    String getSdkName();

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

    /** If work around for dsymutil should be used. */
    Optional<Boolean> getWorkAroundDsymutilLtoStackOverflowBug();
  }
}
