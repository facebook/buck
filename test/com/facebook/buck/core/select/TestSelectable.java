/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.select;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

public class TestSelectable implements Selectable {

  private final UnconfiguredBuildTargetView buildTarget;
  private final boolean matches;
  private final Map<UnconfiguredBuildTargetView, Boolean> refinedTargets;

  public TestSelectable(
      UnconfiguredBuildTargetView buildTarget,
      boolean matches,
      Map<UnconfiguredBuildTargetView, Boolean> refinedTargets) {
    this.buildTarget = buildTarget;
    this.matches = matches;
    this.refinedTargets = refinedTargets;
  }

  public TestSelectable(UnconfiguredBuildTargetView buildTarget, boolean matches) {
    this(buildTarget, matches, ImmutableMap.of());
  }

  @Override
  public boolean matches(SelectableConfigurationContext configurationContext) {
    return matches;
  }

  @Override
  public boolean refines(Selectable other) {
    if (refinedTargets.containsKey(other.getBuildTarget())) {
      return refinedTargets.get(other.getBuildTarget());
    }
    return false;
  }

  @Override
  public UnconfiguredBuildTargetView getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String toString() {
    return buildTarget.getFullyQualifiedName();
  }
}
