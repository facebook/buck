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
package com.facebook.buck.android.toolchain.platform;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.platform.MultiPlatformRule;
import com.facebook.buck.core.rules.platform.RuleBasedPlatformResolver;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;

/** Buck rule representing Android multiplatform definition */
public class AndroidMultiPlatformRule implements MultiPlatformRule {

  private final BuildTarget buildTarget;
  private final BuildTarget basePlatform;
  private final ImmutableSortedMap<TargetCpuType, BuildTarget> nestedPlatforms;

  public AndroidMultiPlatformRule(
      BuildTarget buildTarget,
      BuildTarget basePlatform,
      ImmutableSortedMap<TargetCpuType, BuildTarget> nestedPlatforms) {
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
    Platform basePlatform = ruleBasedPlatformResolver.getPlatform(this.basePlatform);

    ImmutableSortedMap.Builder<TargetCpuType, NamedPlatform> nestedPlatforms =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<TargetCpuType, BuildTarget> e : this.nestedPlatforms.entrySet()) {
      nestedPlatforms.put(e.getKey(), ruleBasedPlatformResolver.getPlatform(e.getValue()));
    }

    return new AndroidMultiPlatform(buildTarget, basePlatform, nestedPlatforms.build());
  }
}
