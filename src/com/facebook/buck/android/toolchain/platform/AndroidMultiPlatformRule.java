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

package com.facebook.buck.android.toolchain.platform;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.config.AbstractConfigurationRule;
import com.facebook.buck.core.rules.platform.MultiPlatformRule;
import com.facebook.buck.core.rules.platform.RuleBasedPlatformResolver;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;

/** Buck rule representing Android multiplatform definition */
public class AndroidMultiPlatformRule extends AbstractConfigurationRule
    implements MultiPlatformRule {

  private final BuildTarget basePlatform;
  private final ImmutableSortedMap<TargetCpuType, BuildTarget> nestedPlatforms;

  public AndroidMultiPlatformRule(
      BuildTarget buildTarget,
      BuildTarget basePlatform,
      ImmutableSortedMap<TargetCpuType, BuildTarget> nestedPlatforms) {
    super(buildTarget);
    this.basePlatform = basePlatform;
    this.nestedPlatforms = nestedPlatforms;
  }

  @Override
  public Platform createPlatform(
      RuleBasedPlatformResolver ruleBasedPlatformResolver, DependencyStack dependencyStack) {
    Platform basePlatform =
        ruleBasedPlatformResolver.getPlatform(this.basePlatform, dependencyStack);

    ImmutableSortedMap.Builder<TargetCpuType, NamedPlatform> nestedPlatforms =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<TargetCpuType, BuildTarget> e : this.nestedPlatforms.entrySet()) {
      nestedPlatforms.put(
          e.getKey(),
          ruleBasedPlatformResolver.getPlatform(e.getValue(), dependencyStack.child(e.getValue())));
    }

    return new AndroidMultiPlatform(getBuildTarget(), basePlatform, nestedPlatforms.build());
  }
}
