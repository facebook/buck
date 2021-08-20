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

package com.facebook.buck.support.slowtargets;

import com.facebook.buck.core.model.BuildTarget;

/**
 * A slow target, as identified by {@link TopSlowTargetsBuilder}. Otherwise is just a tuple of a
 * {@link BuildTarget} and the total duration of that build target's execution.
 */
public final class SlowTarget {
  /** The target in question. */
  private final BuildTarget target;

  /** The duration that the target spent executing, in milliseconds. */
  private final long durationMilliseconds;

  /** The time when the target began executing, as a timestamp in milliseconds. */
  private final long startTimeMilliseconds;

  /** The size of the target's output size, or zero if not applicable. */
  private long outputSize;

  public SlowTarget(BuildTarget target, long durationMilliseconds, long startTimeMilliseconds) {
    this.target = target;
    this.durationMilliseconds = durationMilliseconds;
    this.startTimeMilliseconds = startTimeMilliseconds;
    this.outputSize = 0;
  }

  public BuildTarget getTarget() {
    return target;
  }

  public long getDurationMilliseconds() {
    return durationMilliseconds;
  }

  public long getStartTimeMilliseconds() {
    return startTimeMilliseconds;
  }

  public long getOutputSize() {
    return outputSize;
  }

  void setOutputSize(long outputSize) {
    this.outputSize = outputSize;
  }
}
