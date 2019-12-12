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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.platform.MultiPlatform;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSortedMap;

/** Android-specific implementation of multi-platform. */
public class AndroidMultiPlatform implements MultiPlatform {

  private final BuildTarget buildTarget;
  private final Platform basePlatform;
  private final ImmutableSortedMap<TargetCpuType, NamedPlatform> nestedPlatforms;

  /**
   * Creates an instance of {@link AndroidMultiPlatform}.
   *
   * <p>A multiplatform is a platform the has a base platform and multiple nested platforms. Base
   * platform is the platform that is used in the context that expects a single platform. Nested
   * platforms a handled by special logic in particular places. For example, when the same target
   * needs to be built for every platform specified in nested platform the multiplatform is
   * processed by duplicating targets with nested platforms in configurations.
   */
  public AndroidMultiPlatform(
      BuildTarget buildTarget,
      Platform basePlatform,
      ImmutableSortedMap<TargetCpuType, NamedPlatform> nestedPlatforms) {
    ConfigurationForConfigurationTargets.validateTarget(buildTarget);
    this.buildTarget = buildTarget;
    this.basePlatform = basePlatform;
    this.nestedPlatforms = nestedPlatforms;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public Platform getBasePlatform() {
    return basePlatform;
  }

  @Override
  public ImmutableCollection<NamedPlatform> getNestedPlatforms() {
    return nestedPlatforms.values();
  }

  /**
   * Android platform is a multiplatform which maps knows CPUs to nested platforms. This functions
   * returns that mapping.
   */
  public ImmutableSortedMap<TargetCpuType, NamedPlatform> getNestedPlatformsByCpuType() {
    return nestedPlatforms;
  }
}
