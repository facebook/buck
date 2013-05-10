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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Genrule;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.List;
import java.util.Map;

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
public class ApkGenrule extends Genrule implements InstallableBuildRule {

  private final InstallableBuildRule apk;

  private ApkGenrule(BuildRuleParams buildRuleParams,
      List<String> srcs,
      String cmd,
      Function<String, String> relativeToAbsolutePathFunction,
      InstallableBuildRule apk) {
    super(buildRuleParams,
        srcs,
        cmd,
        /* out */ buildRuleParams.getBuildTarget().getShortName() + ".apk",
        relativeToAbsolutePathFunction);

    this.apk = Preconditions.checkNotNull(apk);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.APK_GENRULE;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("apk", apk);
  }

  public InstallableBuildRule getInstallableBuildRule() {
    return apk;
  }

  @Override
  public String getManifest() {
    return apk.getManifest();
  }

  @Override
  public String getApkPath() {
    return getOutputFilePath();
  }

  @Override
  public File getOutput() {
    return new File(getApkPath());
  }

  public static Builder newApkGenruleBuilder() {
    return new Builder();
  }

  @Override
  protected void addEnvironmentVariables(
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(environmentVariablesBuilder);
    environmentVariablesBuilder.put("APK", apk.getApkPath());
  }

  public static class Builder extends Genrule.Builder {

    private String apk;

    @Override
    public ApkGenrule build(Map<String, BuildRule> buildRuleIndex) {
      // Verify that the 'apk' field is set and corresponds to an installable rule.
      BuildRule apkRule = buildRuleIndex.get(apk);

      Preconditions.checkState(apk != null && apkRule != null,
          "Buck should guarantee that apk was set and included in the deps of this rule, " +
          "so apk should not be null at this point and should have an entry in buildRuleIndex " +
          "as all deps should.");

      if (!(apkRule instanceof InstallableBuildRule)) {
        throw new HumanReadableException("The 'apk' argument of %s, %s, must correspond to an " +
        		"installable rule, such as android_binary() or apk_genrule().",
        		getBuildTarget(),
            apkRule.getFullyQualifiedName());
      }

      return new ApkGenrule(createBuildRuleParams(buildRuleIndex),
          srcs,
          cmd,
          relativeToAbsolutePathFunction,
          (InstallableBuildRule)apkRule);
    }

    public Builder setApk(String apk) {
      this.apk = apk;
      return this;
    }
  }
}
