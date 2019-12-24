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

package com.facebook.buck.core.select;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

public class TestSelectable implements Selectable {

  private final BuildTarget buildTarget;
  private final boolean matches;
  private final Map<BuildTarget, Boolean> refinedTargets;

  public TestSelectable(
      BuildTarget buildTarget, boolean matches, Map<BuildTarget, Boolean> refinedTargets) {
    this.buildTarget = buildTarget;
    this.matches = matches;
    this.refinedTargets = refinedTargets;
  }

  public TestSelectable(BuildTarget buildTarget, boolean matches) {
    this(buildTarget, matches, ImmutableMap.of());
  }

  @Override
  public boolean matches(
      SelectableConfigurationContext configurationContext, DependencyStack dependencyStack) {
    return matches;
  }

  @Override
  public boolean matchesPlatform(
      Platform platform,
      ConstraintResolver constraintResolver,
      DependencyStack dependencyStack,
      BuckConfig buckConfig) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public boolean refines(Selectable other) {
    if (refinedTargets.containsKey(other.getBuildTarget())) {
      return refinedTargets.get(other.getBuildTarget());
    }
    return false;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String toString() {
    return buildTarget.getFullyQualifiedName();
  }
}
