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
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
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

  private ApkGenrule(BuildRuleParams buildRuleParams,
      List<Path> srcs,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      Function<Path, Path> relativeToAbsolutePathFunction,
      InstallableApk apk) {
    super(buildRuleParams,
        srcs,
        cmd,
        bash,
        cmdExe,
        /* out */ buildRuleParams.getBuildTarget().getShortName() + ".apk",
        relativeToAbsolutePathFunction);

    this.apk = Preconditions.checkNotNull(apk);
    this.relativeToAbsolutePathFunction =
        Preconditions.checkNotNull(relativeToAbsolutePathFunction);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.APK_GENRULE;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .setInput("apk", apk.getApkPath());
  }

  public InstallableApk getInstallableApk() {
    return apk;
  }

  @Override
  public SourcePath getManifest() {
    return apk.getManifest();
  }

  @Override
  public Path getApkPath() {
    return getPathToOutputFile();
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return super.getInputsToCompareToOutput();
  }

  public static Builder newApkGenruleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
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

  public static class Builder extends Genrule.Builder {

    private BuildTarget apk;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public ApkGenrule build(BuildRuleResolver ruleResolver) {
      // Verify that the 'apk' field is set and corresponds to an installable rule.

      BuildRule apkRule = ruleResolver.get(apk);

      Preconditions.checkState(apk != null && apkRule != null,
          "Buck should guarantee that apk was set and included in the deps of this rule, " +
          "so apk should not be null at this point and should have an entry in buildRuleIndex " +
          "as all deps should.");

      if (!(apkRule.getBuildable() instanceof InstallableApk)) {
        throw new HumanReadableException("The 'apk' argument of %s, %s, must correspond to an " +
        		"installable rule, such as android_binary() or apk_genrule().",
        		getBuildTarget(),
            apkRule.getFullyQualifiedName());
      }

      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new ApkGenrule(createBuildRuleParams(ruleResolver),
          srcs,
          cmd,
          bash,
          cmdExe,
          getRelativeToAbsolutePathFunction(buildRuleParams),
          (InstallableApk)apkRule.getBuildable());
    }

    public Builder setApk(BuildTarget apk) {
      this.apk = apk;
      return this;
    }
  }
}
