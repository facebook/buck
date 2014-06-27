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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class FakeBuildRule extends AbstractBuildRule implements BuildRule {


  @Nullable
  private Path outputFile;

  @Nullable
  private RuleKey ruleKey;

  public FakeBuildRule(BuildRuleType type,
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(deps)
            .setType(type)
            .setVisibility(visibilityPatterns)
            .build());
  }

  public FakeBuildRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
  }

  public FakeBuildRule(BuildRuleType type, BuildTarget buildTarget) {
    this(new FakeBuildRuleParamsBuilder(buildTarget).setType(type).build());
  }
  @Override
  public Iterable<Path> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutputFile() {
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
    if (ruleKey != null) {
      return ruleKey;
    } else {
      throw new IllegalStateException("This method should not be called");
    }
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    throw new IllegalStateException("This method should not be called");
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    throw new IllegalStateException("This method should not be called");
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    throw new UnsupportedOperationException();
  }
}
