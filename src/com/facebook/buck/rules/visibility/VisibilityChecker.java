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
  public Optional<VisibilityError> isVisibleTo(ObeysVisibility viewer) {
    // Check that the owner (dep) is within_view of the viewer (node).s
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
            ImmutableVisibilityError.ofImpl(
                VisibilityError.ErrorType.WITHIN_VIEW,
                viewer.getBuildTarget(),
                owner.getBuildTarget()));
      }
    }

    // Nodes in the same package are always visible to other nodes in the package, so we can skip
    // visibility checking.
    if (owner
        .getBuildTarget()
        .getCellRelativeBasePath()
        .equals(viewer.getBuildTarget().getCellRelativeBasePath())) {
      return Optional.empty();
    }

    // Check that the owner (dep) is visible to the viewer (node).
    for (VisibilityPattern pattern : visibilityPatterns) {
      if (pattern.checkVisibility(viewer)) {
        return Optional.empty();
      }
    }

    return Optional.of(
        ImmutableVisibilityError.ofImpl(
            VisibilityError.ErrorType.VISIBILITY, viewer.getBuildTarget(), owner.getBuildTarget()));
  }
}
