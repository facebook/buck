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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;

import javax.annotation.Nullable;

public class FakeBuildRule extends AbstractBuildRule implements BuildRule {

  private final BuildRuleType type;

  @Nullable
  private String outputFile;

  public FakeBuildRule(BuildRuleType type,
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    this(type, new BuildRuleParams(target, deps, visibilityPatterns));
  }

  public FakeBuildRule(BuildRuleType type, BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.type = Preconditions.checkNotNull(type);
  }

  @Override
  public BuildRuleType getType() {
    return type;
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
    throw new UnsupportedOperationException("build() not supported in fake");
  }

  @Override
  public BuildRuleSuccess.Type getBuildResultType() {
    throw new UnsupportedOperationException("getBuildResultType() not supported in fake");
  }

  @Override
  public final Iterable<InputRule> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public String getPathToOutputFile() {
    return outputFile;
  }

  public void setOutputFile(String outputFile) {
    this.outputFile = outputFile;
  }

  @Override
  public RuleKey.Builder ruleKeyBuilder() {
    throw new IllegalStateException("This method should not be called");
  }
}
