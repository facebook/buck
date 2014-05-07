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

import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Default implementation of {@link BuildableParams}.
 */
public class DefaultBuildableParams implements BuildableParams {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> deps;

  public DefaultBuildableParams(
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> deps) {
    this.buildTarget = buildTarget;
    this.deps = deps;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDeps() {
    return deps;
  }
}
