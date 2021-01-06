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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleDescription;
import java.util.Optional;
import org.immutables.value.Value;

public class ApkGenruleDescription extends AbstractGenruleDescription<ApkGenruleDescriptionArg> {

  public ApkGenruleDescription(
      ToolchainProvider toolchainProvider,
      BuckConfig config,
      SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, config, sandboxExecutionStrategy, false);
  }

  @Override
  public Class<ApkGenruleDescriptionArg> getConstructorArgType() {
    return ApkGenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ApkGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {

    BuildRule apk = graphBuilder.getRule(args.getApk());
    if (!(apk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "The 'apk' argument of %s, %s, must correspond to an "
              + "installable rule, such as android_binary() or apk_genrule().",
          buildTarget, args.getApk().getFullyQualifiedName());
    }

    HasInstallableApk installableApk = (HasInstallableApk) apk;
    return new ApkGenrule(
        buildTarget,
        projectFilesystem,
        sandboxExecutionStrategy,
        graphBuilder,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        args.getIsCacheable(),
        args.getEnvironmentExpansionSeparator(),
        args.isNeedAndroidTools()
            ? Optional.of(
                AndroidTools.getAndroidTools(
                    toolchainProvider, buildTarget.getTargetConfiguration()))
            : Optional.empty(),
        installableApk);
  }

  @RuleArg
  interface AbstractApkGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    Optional<String> getOut();

    BuildTarget getApk();

    @Override
    default Optional<String> getType() {
      return Optional.of("apk");
    }

    // TODO(T32241734): Cleanup uses of `is_cacheable` and remove, favoring `cacheable` instead.
    @Value.Default
    default boolean getIsCacheable() {
      return getCacheable().orElse(true);
    }
  }

  static AndroidBinary getUnderlyingApk(HasInstallableApk installable) {
    if (installable instanceof AndroidBinary) {
      return (AndroidBinary) installable;
    } else if (installable instanceof ApkGenrule) {
      return getUnderlyingApk(((ApkGenrule) installable).getInstallableApk());
    } else {
      throw new IllegalStateException(
          installable.getBuildTarget().getFullyQualifiedName()
              + " must be backed by either an android_binary() or an apk_genrule()");
    }
  }
}
