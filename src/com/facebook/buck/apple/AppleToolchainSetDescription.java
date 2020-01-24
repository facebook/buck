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
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;

/**
 * Defines an apple_toolchain_set rule that allows to list all available apple_toolchain targets
 * which will be used to create {@link AppleCxxPlatform}.
 */
public class AppleToolchainSetDescription
    implements DescriptionWithTargetGraph<AppleToolchainSetDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleToolchainSetDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());

    ImmutableList.Builder<AppleCxxPlatform> appleCxxPlatformsBuilder = ImmutableList.builder();
    for (BuildTarget target : args.getAppleToolchains()) {
      BuildRule appleToolchainRule = context.getActionGraphBuilder().getRule(target);
      if (!(appleToolchainRule instanceof AppleToolchainBuildRule)) {
        throw new HumanReadableException(
            "Expected %s to be an instance of apple_toolchain.", target);
      }
      appleCxxPlatformsBuilder.add(
          ((AppleToolchainBuildRule) appleToolchainRule).getAppleCxxPlatform());
    }
    return new AppleToolchainSetBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        FlavorDomain.from("Apple C++ Platform", appleCxxPlatformsBuilder.build()));
  }

  @Override
  public Class<AppleToolchainSetDescriptionArg> getConstructorArgType() {
    return AppleToolchainSetDescriptionArg.class;
  }

  /** An apple_toolchain_set is a list of available apple_toolchain targets. */
  @RuleArg
  interface AbstractAppleToolchainSetDescriptionArg extends BuildRuleArg {
    /** List of available toolchains in apple_toolchain targets. */
    ImmutableList<BuildTarget> getAppleToolchains();
  }
}
