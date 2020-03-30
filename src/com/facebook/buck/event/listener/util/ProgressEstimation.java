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

package com.facebook.buck.event.listener.util;

import java.util.Optional;

/** Progress information */
public class ProgressEstimation {
  public static final ProgressEstimation UNKNOWN =
      new ProgressEstimation(Optional.empty(), Optional.empty());

  /** A number between 0 and 1 estimating a progress */
  private final Optional<Double> progress;
  /** Number of something processed (eg number of files parsed) */
  private final Optional<Integer> number;

  public ProgressEstimation(Optional<Double> progress, Optional<Integer> number) {
    this.progress = progress;
    this.number = number;
  }

  public Optional<Double> getProgress() {
    return progress;
  }

  public Optional<Integer> getNumber() {
    return number;
  }
}
