/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.Genrule;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Rule for generating an apple package via external script. */
public class ExternallyBuiltApplePackage extends Genrule {
  private ApplePackageConfigAndPlatformInfo packageConfigAndPlatformInfo;

  public ExternallyBuiltApplePackage(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      BuildRuleResolver resolver,
      BuildRuleParams params,
      ApplePackageConfigAndPlatformInfo packageConfigAndPlatformInfo,
      SourcePath bundle,
      boolean cacheable,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<AndroidNdk> androidNdk,
      Optional<AndroidSdkLocation> androidSdkLocation) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        params,
        sandboxExecutionStrategy,
        SourceSet.ofUnnamedSources(ImmutableSortedSet.of(bundle)),
        Optional.of(packageConfigAndPlatformInfo.getExpandedArg()),
        /* bash */ Optional.empty(),
        /* cmdExe */ Optional.empty(),
        /* type */ Optional.empty(),
        buildTarget.getShortName() + "." + packageConfigAndPlatformInfo.getConfig().getExtension(),
        false,
        cacheable,
        environmentExpansionSeparator,
        androidPlatformTarget,
        androidNdk,
        androidSdkLocation,
        false);
    this.packageConfigAndPlatformInfo = packageConfigAndPlatformInfo;
  }

  @Override
  protected void addEnvironmentVariables(
      SourcePathResolver pathResolver,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(pathResolver, environmentVariablesBuilder);
    environmentVariablesBuilder.put(
        "SDKROOT", packageConfigAndPlatformInfo.getSdkPath().toString());
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("sdkVersion", packageConfigAndPlatformInfo.getSdkVersion())
        .setReflectively("buildVersion", packageConfigAndPlatformInfo.getPlatformBuildVersion());
  }

  /** Value type for tracking a package config and information about the platform. */
  @Value.Immutable
  @BuckStyleTuple
  abstract static class AbstractApplePackageConfigAndPlatformInfo {
    public abstract ApplePackageConfig getConfig();

    /**
     * The apple cxx platform in question.
     *
     * <p>As this value is architecture specific, it is omitted from equality computation, via
     * {@code Value.Auxiliary}. Since the actual apple "Platform" is architecture agnostic, proxy
     * values for the actual platform are used for equality comparison instead.
     */
    @Value.Auxiliary
    protected abstract AppleCxxPlatform getPlatform();

    /**
     * The sdk version of the platform.
     *
     * <p>This is used as a proxy for the version of the external packager.
     */
    @Value.Derived
    public String getSdkVersion() {
      return getPlatform().getAppleSdk().getVersion();
    }

    /**
     * The build version of the platform.
     *
     * <p>This is used as a proxy for the version of the external packager.
     */
    @Value.Derived
    public Optional<String> getPlatformBuildVersion() {
      return getPlatform().getBuildVersion();
    }

    /** Returns the Apple SDK path. */
    @Value.Derived
    public Path getSdkPath() {
      return getPlatform().getAppleSdkPaths().getSdkPath();
    }

    /** Command after passing through argument expansion. */
    @Value.Derived
    @Value.Auxiliary
    public Arg getExpandedArg() {
      return StringArg.of(getConfig().getCommand());
    }
  }
}
