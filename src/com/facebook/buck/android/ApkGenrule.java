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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.List;

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
  private final InstallableApk apk;
  private final Function<Path, Path> relativeToAbsolutePathFunction;

  ApkGenrule(
      BuildTarget target,
      List<Path> srcs,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      Function<Path, Path> relativeToAbsolutePathFunction,
      InstallableApk apk) {
    super(target,
        srcs,
        cmd,
        bash,
        cmdExe,
        /* out */ target.getShortName() + ".apk",
        relativeToAbsolutePathFunction);

    this.apk = Preconditions.checkNotNull(apk);
    this.relativeToAbsolutePathFunction =
        Preconditions.checkNotNull(relativeToAbsolutePathFunction);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return super.appendDetailsToRuleKey(builder)
        .setInput("apk", apk.getApkPath());
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  public InstallableApk getInstallableApk() {
    return apk;
  }

  @Override
  public Path getManifestPath() {
    return apk.getManifestPath();
  }

  @Override
  public Path getApkPath() {
    return getPathToOutputFile();
  }

  @Override
  public Optional<ExopackageInfo> getExopackageInfo() {
    return apk.getExopackageInfo();
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return super.getInputsToCompareToOutput();
  }

  @Override
  protected BuildRuleType getType() {
    return ApkGenruleDescription.TYPE;
  }

  @Override
  protected void addEnvironmentVariables(
      ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(context, environmentVariablesBuilder);
    // We have to use an absolute path, because genrules are run in a temp directory.
    String apkAbsolutePath = relativeToAbsolutePathFunction.apply(apk.getApkPath()).toString();
    environmentVariablesBuilder.put("APK", apkAbsolutePath);
  }
}
