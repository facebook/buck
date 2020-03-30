/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.config.AbstractConfigurationRule;
import com.google.common.collect.ImmutableList;

public class FakeMultiPlatformRule extends AbstractConfigurationRule implements MultiPlatformRule {

  private final BuildTarget basePlatform;
  private final ImmutableList<BuildTarget> nestedPlatforms;

  public FakeMultiPlatformRule(
      BuildTarget buildTarget,
      BuildTarget basePlatform,
      ImmutableList<BuildTarget> nestedPlatforms) {
    super(buildTarget);
    this.basePlatform = basePlatform;
    this.nestedPlatforms = nestedPlatforms;
  }

  @Override
  public Platform createPlatform(
      RuleBasedPlatformResolver ruleBasedPlatformResolver, DependencyStack dependencyStack) {
    NamedPlatform basePlatform =
        ruleBasedPlatformResolver.getPlatform(this.basePlatform, dependencyStack);

    ImmutableList<NamedPlatform> nestedPlatforms =
        this.nestedPlatforms.stream()
            .map(
                (BuildTarget buildTarget1) ->
                    ruleBasedPlatformResolver.getPlatform(
                        buildTarget1, dependencyStack.child(buildTarget1)))
            .collect(ImmutableList.toImmutableList());

    return new FakeMultiPlatform(getBuildTarget(), basePlatform, nestedPlatforms);
  }
}
