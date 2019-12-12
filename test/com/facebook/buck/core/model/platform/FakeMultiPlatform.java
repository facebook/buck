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

package com.facebook.buck.core.model.platform;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

/** Basic multi-platform implementation used in tests */
public class FakeMultiPlatform implements MultiPlatform {
  private final NamedPlatform basePlatform;
  private final BuildTarget buildTarget;
  private final ImmutableList<NamedPlatform> nestedPlatforms;

  public FakeMultiPlatform(
      BuildTarget buildTarget,
      NamedPlatform basePlatform,
      ImmutableList<NamedPlatform> nestedPlatforms) {
    ConfigurationForConfigurationTargets.validateTarget(buildTarget);
    this.basePlatform = basePlatform;
    this.buildTarget = buildTarget;
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
    return nestedPlatforms;
  }
}
