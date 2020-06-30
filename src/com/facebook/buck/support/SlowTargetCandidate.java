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

package com.facebook.buck.support;

import com.facebook.buck.core.model.BuildTarget;
import javax.annotation.Nullable;

/**
 * A candidate slow target, as kept track of by {@link TopSlowTargetsBuilder}. Forms a doubly-linked
 * list with other candidate notes.
 */
final class SlowTargetCandidate {
  private final BuildTarget target;

  private final long durationMilliseconds;

  @Nullable private SlowTargetCandidate nextSlowestTarget;

  @Nullable private SlowTargetCandidate prevSlowestTarget;

  public SlowTargetCandidate(BuildTarget target, long durationMilliseconds) {
    this.target = target;
    this.durationMilliseconds = durationMilliseconds;
  }

  public BuildTarget getTarget() {
    return target;
  }

  public long getDurationMilliseconds() {
    return durationMilliseconds;
  }

  @Nullable
  public SlowTargetCandidate getNextSlowestTarget() {
    return nextSlowestTarget;
  }

  public void setNextSlowestTarget(@Nullable SlowTargetCandidate nextSlowestRule) {
    this.nextSlowestTarget = nextSlowestRule;
  }

  @Nullable
  public SlowTargetCandidate getPrevSlowestTarget() {
    return prevSlowestTarget;
  }

  public void setPrevSlowestTarget(@Nullable SlowTargetCandidate prevSlowestRule) {
    this.prevSlowestTarget = prevSlowestRule;
  }
}
