/*
 * Copyright 2013-present Facebook, Inc.
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
import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A {@link BuildRule} that is constructed from a {@link Description}.
 */
// TODO(simons): Delete once everything has migrated to using Buildables.
@Beta
public class DescribedRule extends AbstractBuildRule {

  public DescribedRule(BuildRuleParams params) {
    super(params);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return null;
  }

  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return null;
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return null;
  }
}
