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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class FakeBuildRule extends AbstractBuildRule implements BuildRule {


  @Nullable
  private Path outputFile;

  @Nullable
  private RuleKey ruleKey;

  public FakeBuildRule(
      BuildTarget target,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(deps)
            .build(), resolver);
  }

  public FakeBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    super(buildRuleParams, resolver);
  }

  public FakeBuildRule(BuildTarget buildTarget, SourcePathResolver resolver) {
    this(new FakeBuildRuleParamsBuilder(buildTarget).build(), resolver);
  }

  public FakeBuildRule(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      BuildRule... deps) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(), resolver);
  }

  public FakeBuildRule(String target, SourcePathResolver resolver, BuildRule... deps) {
    this(BuildTargetFactory.newInstance(target), new FakeProjectFilesystem(), resolver, deps);
  }

  @Override
  public Path getPathToOutput() {
    return outputFile;
  }

  public void setOutputFile(String outputFile) {
    this.outputFile = Paths.get(outputFile);
  }

  public void setRuleKey(RuleKey ruleKey) {
    this.ruleKey = ruleKey;
  }

  @Override
  public RuleKey getRuleKey() {
    if (ruleKey == null) {
      String hashCode = String.valueOf(Math.abs(this.hashCode()));
      ruleKey = new RuleKey(Strings.repeat(hashCode, 40 / hashCode.length() + 1).substring(0, 40));
    }
    return ruleKey;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }
}
