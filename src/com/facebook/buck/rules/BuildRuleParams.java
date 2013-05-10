/*
 * Copyright 2012-present Facebook, Inc.
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
import com.facebook.buck.model.BuildTargetPattern;
import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Standard set of parameters that is passed to all build rules.
 */
@Beta
public final class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> deps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;

  public BuildRuleParams(BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.deps = Preconditions.checkNotNull(deps);
    this.visibilityPatterns = Preconditions.checkNotNull(visibilityPatterns);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public ImmutableSortedSet<BuildRule> getDeps() {
    return deps;
  }

  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return visibilityPatterns;
  }
}
