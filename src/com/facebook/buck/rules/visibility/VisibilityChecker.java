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

package com.facebook.buck.rules.visibility;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

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

  /** Check whether {@code viewer} is within view or visible to this checker. */
  public Optional<HumanReadableException> isVisibleToWithError(ObeysVisibility viewer) {
    if (!viewer.getVisibilityChecker().withinViewPatterns.isEmpty()) {
      boolean withinView = false;
      for (VisibilityPattern pattern : viewer.getVisibilityChecker().withinViewPatterns) {
        if (pattern.checkVisibility(owner)) {
          withinView = true;
          break;
        }
      }
      if (!withinView) {
        return Optional.of(
            new HumanReadableException(
                "%s depends on %s, which is not within view. More info at:\nhttps://buck.build/concept/visibility.html",
                viewer, owner.getBuildTarget()));
      }
    }

    if (owner
        .getBuildTarget()
        .getCellRelativeBasePath()
        .equals(viewer.getBuildTarget().getCellRelativeBasePath())) {
      return Optional.empty();
    }

    for (VisibilityPattern pattern : visibilityPatterns) {
      if (pattern.checkVisibility(viewer)) {
        return Optional.empty();
      }
    }

    return Optional.of(
        new HumanReadableException(
            "%s depends on %s, which is not visible. More info at:\nhttps://buck.build/concept/visibility.html",
            viewer, owner.getBuildTarget()));
  }
}
