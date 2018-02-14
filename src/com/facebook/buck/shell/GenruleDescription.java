/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

public class GenruleDescription extends AbstractGenruleDescription<GenruleDescriptionArg> {

  private final BuckConfig buckConfig;

  public GenruleDescription(
      ToolchainProvider toolchainProvider,
      BuckConfig buckConfig,
      SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, sandboxExecutionStrategy, false);
    this.buckConfig = buckConfig;
  }

  @Override
  public Class<GenruleDescriptionArg> getConstructorArgType() {
    return GenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    Optional<AndroidPlatformTarget> androidPlatformTarget =
        toolchainProvider.getByNameIfPresent(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);
    Optional<AndroidNdk> androidNdk =
        toolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);
    Optional<AndroidSdkLocation> androidSdkLocation =
        toolchainProvider.getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);

    if (!args.getExecutable().orElse(false)) {
      SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
      return new Genrule(
          buildTarget,
          projectFilesystem,
          resolver,
          params,
          sandboxExecutionStrategy,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut(),
          sandboxConfig.isSandboxEnabledForCurrentPlatform()
              && args.getEnableSandbox().orElse(sandboxConfig.isGenruleSandboxEnabled()),
          args.getCacheable().orElse(true),
          args.getEnvironmentExpansionSeparator(),
          androidPlatformTarget,
          androidNdk,
          androidSdkLocation);
    } else {
      return new GenruleBinary(
          buildTarget,
          projectFilesystem,
          sandboxExecutionStrategy,
          resolver,
          params,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut(),
          args.getCacheable().orElse(true),
          args.getEnvironmentExpansionSeparator(),
          androidPlatformTarget,
          androidNdk,
          androidSdkLocation);
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    String getOut();

    Optional<Boolean> getExecutable();

    /**
     * This functionality only exists to get around the lack of extensibility in our current build
     * rule / build file apis. It may go away at some point. Also, make sure that you understand
     * what {@link BuildRule.isCacheable} does with respect to caching if you decide to use this
     * attribute
     */
    Optional<Boolean> getCacheable();
  }
}
