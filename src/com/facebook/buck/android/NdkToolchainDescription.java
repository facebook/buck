/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.google.common.base.Verify;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Defines an ndk_toolchain rule that allows a {@link NdkCxxPlatform} to be configured as a build
 * target.
 */
public class NdkToolchainDescription
    implements DescriptionWithTargetGraph<NdkToolchainDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      NdkToolchainDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());

    BuildRule cxxPlatformRule = context.getActionGraphBuilder().getRule(args.getCxxToolchain());
    if (!(cxxPlatformRule instanceof ProvidesCxxPlatform)) {
      throw new HumanReadableException(
          "Expected %s to be an instance of cxx_platform.", cxxPlatformRule.getBuildTarget());
    }

    return new NdkToolchainBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        (ProvidesCxxPlatform) cxxPlatformRule,
        args.getSharedRuntimePath(),
        args.getCxxRuntime(),
        Tools.resolveTool(args.getObjdump(), context.getActionGraphBuilder()));
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return false;
  }

  @Override
  public Class<NdkToolchainDescriptionArg> getConstructorArgType() {
    return NdkToolchainDescriptionArg.class;
  }

  /** An ndk_toolchain is mostly just a cxx_toolchain and a few other fields. */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractNdkToolchainDescriptionArg extends CommonDescriptionArg {

    /** Target for the cxx toolchain part of the ndk toolchain. */
    BuildTarget getCxxToolchain();

    /** Path for the c++ shared runtime to package in the apk. */
    Optional<SourcePath> getSharedRuntimePath();

    /** Objdump binary. */
    SourcePath getObjdump();

    /** Ndk runtime type. */
    NdkCxxRuntime getCxxRuntime();
  }
}
