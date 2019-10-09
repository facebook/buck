/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableList;

public class FakeMultiPlatformRule implements MultiPlatformRule {

  private final BuildTarget buildTarget;
  private final BuildTarget basePlatform;
  private final ImmutableList<BuildTarget> nestedPlatforms;

  public FakeMultiPlatformRule(
      BuildTarget buildTarget,
      BuildTarget basePlatform,
      ImmutableList<BuildTarget> nestedPlatforms) {
    this.buildTarget = buildTarget;
    this.basePlatform = basePlatform;
    this.nestedPlatforms = nestedPlatforms;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public Platform createPlatform(RuleBasedPlatformResolver ruleBasedPlatformResolver) {
    NamedPlatform basePlatform = ruleBasedPlatformResolver.getPlatform(this.basePlatform);

    ImmutableList<NamedPlatform> nestedPlatforms =
        this.nestedPlatforms.stream()
            .map(ruleBasedPlatformResolver::getPlatform)
            .collect(ImmutableList.toImmutableList());

    return new FakeMultiPlatform(buildTarget, basePlatform, nestedPlatforms);
  }
}
