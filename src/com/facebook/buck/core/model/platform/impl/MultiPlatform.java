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
package com.facebook.buck.core.model.platform.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableList;
import java.util.Collection;

/**
 * A generic platform that encapsulates multiple other platforms. This is used to support building
 * packages and binaries that support multiple platforms (for example, an Android binary).
 *
 * <p>A multiplatform is a platform that has a base platform and multiple nested platforms. When
 * this platform is used in the context that expects a single platform the base platform is used to
 * match the constraints.
 */
public class MultiPlatform implements Platform {

  private final BuildTarget buildTarget;
  private final Platform basePlatform;
  private final ImmutableList<Platform> nestedPlatforms;

  /**
   * Creates an instance of {@link MultiPlatform}.
   *
   * <p>A multiplatform is a platform the has a base platform and multiple nested platforms. Base
   * platform is the platform that is used in the context that expects a single platform. Nested
   * platforms a handled by special logic in particular places. For example, when the same target
   * needs to be built for every platform specified in nested platform the multiplatform is
   * processed by duplicating targets with nested platforms in configurations.
   */
  public MultiPlatform(
      BuildTarget buildTarget, Platform basePlatform, ImmutableList<Platform> nestedPlatforms) {
    this.buildTarget = buildTarget;
    this.basePlatform = basePlatform;
    this.nestedPlatforms = nestedPlatforms;
  }

  @Override
  public boolean matchesAll(Collection<ConstraintValue> constraintValues) {
    return basePlatform.matchesAll(constraintValues);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public Platform getBasePlatform() {
    return basePlatform;
  }

  public ImmutableList<Platform> getNestedPlatforms() {
    return nestedPlatforms;
  }
}
