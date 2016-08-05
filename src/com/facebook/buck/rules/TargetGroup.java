/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.model.HasBuildTarget;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class TargetGroup implements Iterable<BuildTarget>, HasBuildTarget {

  private final ImmutableSet<BuildTarget> targets;
  private final boolean restrictOutboundVisibility;

  private final BuildTarget buildTarget;

  public TargetGroup(
      Set<BuildTarget> targets,
      Optional<Boolean> restrictOutboundVisibility,
      BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
    this.targets = ImmutableSet.copyOf(targets);
    this.restrictOutboundVisibility = restrictOutboundVisibility.or(false);
  }

  public boolean containsTarget(BuildTarget target) {
    return targets.contains(target);
  }

  public boolean restrictsOutboundVisibility() {
    return restrictOutboundVisibility;
  }

  @Override
  public Iterator<BuildTarget> iterator() {
    return targets.iterator();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public TargetGroup withReplacedTargets(Map<BuildTarget, Iterable<BuildTarget>> replacements) {
    ImmutableSet.Builder<BuildTarget> newTargets = ImmutableSet.builder();
    for (BuildTarget existingTarget : targets) {
      if (replacements.containsKey(existingTarget)) {
        newTargets.addAll(replacements.get(existingTarget));
      } else {
        newTargets.add(existingTarget);
      }
    }
    return new TargetGroup(
        newTargets.build(),
        Optional.of(restrictOutboundVisibility),
        buildTarget);
  }
}
