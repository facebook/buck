/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.model.BuildId;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

/**
 * Decides whether a particular {@link BuildId} belongs to a sampling group of a supplied size.
 */
public class BuildIdSampler implements Function<BuildId, Boolean> {

  public static final Function<Float, BuildIdSampler> CREATE_FUNCTION =
      new Function<Float, BuildIdSampler>() {
        @Override
        public BuildIdSampler apply(Float input) {
          return new BuildIdSampler(input);
        }
      };

  private float sampleRate;

  /**
   * @param sampleRate value in percent between 0 and 100 inclusive. This decides the probability
   *                   of a random {@link BuildId} belongin to the sample.
   */
  public BuildIdSampler(float sampleRate) {
    Preconditions.checkArgument(sampleRate >= 0.0f);
    Preconditions.checkArgument(sampleRate <= 1.0f);
    this.sampleRate = sampleRate;
  }

  /**
   * @param buildId {@link BuildId} to test.
   * @return whether the supplied {@link BuildId} belongs to the sample.
   */
  @Override
  public Boolean apply(BuildId buildId) {
    if (sampleRate == 0.0f) {
      return false;
    } else if (sampleRate == 1.0f) {
      return true;
    }
    String buildIdString = buildId.toString();
    // Naive hashcode implementation so we don't rely on the platform's hashcode implementation.
    int hash = 7;
    for (int i = 0; i < buildIdString.length(); ++i) {
      hash = hash * 31 + buildIdString.charAt(i);
    }
    long range = ((long) Integer.MAX_VALUE) - ((long) Integer.MIN_VALUE);
    long adjustedHash = ((long) hash) - ((long) Integer.MIN_VALUE);
    float scaledHash = ((float) adjustedHash) / range;
    Preconditions.checkState(scaledHash <= 1.0f && scaledHash >= 0.0f);
    return sampleRate > scaledHash;
  }
}
