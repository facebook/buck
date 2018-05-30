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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class ApkGenruleDescription extends AbstractGenruleDescription<ApkGenruleDescriptionArg> {

  public ApkGenruleDescription(
      ToolchainProvider toolchainProvider, SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, sandboxExecutionStrategy, false);
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

    Supplier<? extends SortedSet<BuildRule>> originalExtraDeps = params.getExtraDeps();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    return new ApkGenrule(
        buildTarget,
        projectFilesystem,
        sandboxExecutionStrategy,
        graphBuilder,
        params.withExtraDeps(
            MoreSuppliers.memoize(
                () ->
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(originalExtraDeps.get())
                        .add(apk)
                        .build())),
        ruleFinder,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        apk.getSourcePathToOutput(),
        args.getIsCacheable(),
        args.getEnvironmentExpansionSeparator(),
        toolchainProvider.getByNameIfPresent(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
        toolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class),
        toolchainProvider.getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractApkGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    Optional<String> getOut();

    BuildTarget getApk();

    @Override
    default Optional<String> getType() {
      return Optional.of("apk");
    }

    @Value.Default
    default boolean getIsCacheable() {
      return true;
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
