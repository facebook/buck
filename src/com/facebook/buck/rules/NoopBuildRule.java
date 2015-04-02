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

package com.facebook.buck.rules;

import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A {@link com.facebook.buck.rules.BuildRule} which performs no actions.  This is useful for
 * certain situations where we want presence in the action graph that doesn't actually do anything
 * (e.g. {@link com.facebook.buck.rules.TestRule}.
 */
public class NoopBuildRule extends AbstractBuildRule {

  public NoopBuildRule(BuildRuleParams params, SourcePathResolver resolver) {
    super(params, resolver);
  }

  @Override
  protected final ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  protected final RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public final Path getPathToOutputFile() {
    return null;
  }

}
