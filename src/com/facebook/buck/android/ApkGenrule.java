/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A specialization of a genrule that specifically allows the modification of apks.  This is
 * useful for processes that modify an APK, such as zipaligning it or signing it.
 * <p>
 * The generated APK will be at <code><em>rule_name</em>.apk</code>.
 * <pre>
 * apk_genrule(
 *   name = 'fb4a_signed',
 *   apk = ':fbandroid_release'
 *   deps = [
 *     '//java/com/facebook/sign:fbsign_jar',
 *   ],
 *   cmd = '${//java/com/facebook/sign:fbsign_jar} --input $APK --output $OUT'
 * )
 * </pre>
 */
public class ApkGenrule extends Genrule implements InstallableApk {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID);
  @AddToRuleKey
  private final BuildTargetSourcePath apk;
  private final InstallableApk installableApk;

  ApkGenrule(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      List<SourcePath> srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      SourcePath apk) {
    super(
        params,
        resolver,
        srcs,
        cmd,
        bash,
        cmdExe,
        /* out */ params.getBuildTarget().getShortNameAndFlavorPostfix() + ".apk");

    Preconditions.checkState(apk instanceof BuildTargetSourcePath);
    this.apk = (BuildTargetSourcePath) apk;
    BuildRule rule = ruleFinder.getRuleOrThrow(this.apk);
    Preconditions.checkState(rule instanceof InstallableApk);
    this.installableApk = (InstallableApk) rule;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public InstallableApk getInstallableApk() {
    return installableApk;
  }

  @Override
  public Path getManifestPath() {
    return installableApk.getManifestPath();
  }

  @Override
  public Path getApkPath() {
    return getPathToOutput();
  }

  @Override
  public Optional<ExopackageInfo> getExopackageInfo() {
    return installableApk.getExopackageInfo();
  }

  @Override
  protected void addEnvironmentVariables(
      ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(context, environmentVariablesBuilder);
    // We have to use an absolute path, because genrules are run in a temp directory.
    String apkAbsolutePath = installableApk.getProjectFilesystem()
        .resolve(installableApk.getApkPath())
        .toString();
    environmentVariablesBuilder.put("APK", apkAbsolutePath);
  }
}
