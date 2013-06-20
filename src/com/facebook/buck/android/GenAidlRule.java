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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.List;

/**
 * Build rule for generating a .java file from an .aidl file. Example:
 * <pre>
 * # This will generate IOrcaService.java in the genfiles directory.
 * gen_aidl(
 *   name = 'orcaservice',
 *   aidl = 'IOrcaService.aidl',
 * )
 *
 * # The gen() function flags the input as a file that can be found in the genfiles directory.
 * android_library(
 *   name = 'server',
 *   srcs = glob(['*.java']) + [gen('IOrcaService.java')],
 *   deps = [
 *     '//first-party/orca/lib-base:lib-base',
 *   ],
 * )
 * </pre>
 */
public class GenAidlRule extends AbstractCachingBuildRule {

  private final String aidlFilePath;
  private final String importPath;

  private GenAidlRule(BuildRuleParams buildRuleParams,
      String aidlFilePath,
      String importPath) {
    super(buildRuleParams);
    this.aidlFilePath = Preconditions.checkNotNull(aidlFilePath);
    this.importPath = Preconditions.checkNotNull(importPath);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.GEN_AIDL;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // TODO(#2493457): This rule uses the aidl binary (part of the Android SDK), so the RuleKey
    // should incorporate which version of aidl is used.
    return super.appendToRuleKey(builder)
        .set("aidlFilePath", aidlFilePath)
        .set("importPath", importPath);
  }

  @Override
  protected ImmutableList<String> getInputsToCompareToOutput() {
    return ImmutableList.of(aidlFilePath);
  }

  @Override
  protected List<Step> buildInternal(BuildContext context)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    String destinationDirectory = String.format("%s/%s", BuckConstant.GEN_DIR, importPath);
    commands.add(new MkdirStep(destinationDirectory));

    AidlStep command = new AidlStep(aidlFilePath,
        ImmutableSet.of(importPath),
        destinationDirectory);
    commands.add(command);

    return commands.build();
  }

  public static Builder newGenAidlRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder<GenAidlRule> {

    private String aidl;

    private String importPath;

    private Builder() {}

    @Override
    public GenAidlRule build(BuildRuleResolver ruleResolver) {
      return new GenAidlRule(createBuildRuleParams(ruleResolver), aidl, importPath);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setAidl(String aidl) {
      this.aidl = aidl;
      return this;
    }

    public Builder setImportPath(String importPath) {
      this.importPath = importPath;
      return this;
    }
  }
}
