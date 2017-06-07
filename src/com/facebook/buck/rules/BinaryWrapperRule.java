/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;

/**
 * A no-op stub class for binary rules which delegate to another rule to provide the output path and
 * executable tool.
 */
public abstract class BinaryWrapperRule extends AbstractBuildRule
    implements BinaryBuildRule, HasRuntimeDeps {

  private final SourcePathRuleFinder ruleFinder;

  public BinaryWrapperRule(BuildRuleParams buildRuleParams, SourcePathRuleFinder ruleFinder) {
    super(buildRuleParams);
    this.ruleFinder = ruleFinder;
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public final Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(
            getDeclaredDeps().stream(), getExecutableCommand().getDeps(ruleFinder).stream())
        .map(BuildRule::getBuildTarget);
  }
}
