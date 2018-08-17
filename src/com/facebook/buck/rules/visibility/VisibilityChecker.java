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

package com.facebook.buck.rules.visibility;

import com.google.common.collect.ImmutableSet;

public class VisibilityChecker {
  private final ObeysVisibility owner;
  private final ImmutableSet<VisibilityPattern> visibilityPatterns;
  private final ImmutableSet<VisibilityPattern> withinViewPatterns;

  public VisibilityChecker(
      ObeysVisibility owner,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns) {
    this.owner = owner;
    this.visibilityPatterns = visibilityPatterns;
    this.withinViewPatterns = withinViewPatterns;
  }

  public boolean isVisibleTo(ObeysVisibility viewer) {
    if (!viewer.getVisibilityChecker().withinViewPatterns.isEmpty()) {
      boolean withinView = false;
      for (VisibilityPattern pattern : viewer.getVisibilityChecker().withinViewPatterns) {
        if (pattern.checkVisibility(owner)) {
          withinView = true;
          break;
        }
      }
      if (!withinView) {
        return false;
      }
    }

    if (owner.getBuildTarget().getCellPath().equals(viewer.getBuildTarget().getCellPath())
        && owner.getBuildTarget().getBaseName().equals(viewer.getBuildTarget().getBaseName())) {
      return true;
    }

    for (VisibilityPattern pattern : visibilityPatterns) {
      if (pattern.checkVisibility(viewer)) {
        return true;
      }
    }

    return false;
  }
}
