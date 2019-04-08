/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;
import javax.annotation.Nullable;

public final class FocusedModuleTargetMatcher {
  private static final FocusedModuleTargetMatcher NO_FOCUS = new FocusedModuleTargetMatcher(null);

  private final @Nullable ImmutableSet<UnflavoredBuildTargetView> focusedTargets;

  private FocusedModuleTargetMatcher(
      @Nullable ImmutableSet<UnflavoredBuildTargetView> focusedTargets) {
    this.focusedTargets = focusedTargets;
  }

  /** Returns a matcher that matches everything, i.e. no focus. */
  public static FocusedModuleTargetMatcher noFocus() {
    return NO_FOCUS;
  }

  /** Returns a matcher that specifies a set of targets to focus on. */
  public static FocusedModuleTargetMatcher focusedOn(
      ImmutableSet<UnflavoredBuildTargetView> targets) {
    return new FocusedModuleTargetMatcher(targets);
  }

  /** Returns whether any focus is set. */
  public boolean hasFocus() {
    return focusedTargets != null;
  }

  /**
   * Test whether target matches any focused targets.
   *
   * <p>If there is no focus, always return true.
   */
  public boolean isFocusedOn(BuildTarget buildTarget) {
    if (focusedTargets != null) {
      return focusedTargets.contains(buildTarget.getUnflavoredBuildTarget());
    } else {
      return true;
    }
  }

  /**
   * Apply a mapper to the focused targets held in this object. If no focus is set, this is a no-op.
   */
  public FocusedModuleTargetMatcher map(
      Function<ImmutableSet<UnflavoredBuildTargetView>, ImmutableSet<UnflavoredBuildTargetView>>
          mapper) {
    if (focusedTargets != null) {
      return FocusedModuleTargetMatcher.focusedOn(mapper.apply(focusedTargets));
    } else {
      return this;
    }
  }
}
